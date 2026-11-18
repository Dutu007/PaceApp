package main

import (
	"context"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

var migrationNumberPattern = regexp.MustCompile(`^(\d+)`)

type server struct {
	auth    authConfig
	hermes  *hermesClient
	entries entryStore
	docs    *docStore
	apkDir  string
}

type loginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

type userJSON struct {
	Username    string `json:"username"`
	DisplayName string `json:"display_name"`
}

type authResponse struct {
	Token     string   `json:"token"`
	ExpiresAt string   `json:"expires_at"`
	User      userJSON `json:"user"`
}

type appReleaseJSON struct {
	VersionCode int    `json:"version_code"`
	VersionName string `json:"version_name"`
	Notes       string `json:"notes"`
	Sha256      string `json:"sha256"`
	Size        int64  `json:"size"`
}

type poolStore struct {
	pool *pgxpool.Pool
}

func (p *poolStore) List(ctx context.Context, f entryFilter) ([]entryItem, error) {
	args := make([]any, 0, 4)
	conds := []string{"1=1"}
	if f.Kind != "" && f.Kind != "all" {
		args = append(args, f.Kind)
		conds = append(conds, fmt.Sprintf("kind = $%d", len(args)))
	}
	if f.Pinned != nil {
		args = append(args, *f.Pinned)
		conds = append(conds, fmt.Sprintf("pinned = $%d", len(args)))
	}
	if f.Query != "" {
		args = append(args, "%"+f.Query+"%")
		conds = append(conds, fmt.Sprintf("(title ilike $%d or content ilike $%d)", len(args), len(args)))
	}
	if f.Tag != "" {
		tagJSON, _ := json.Marshal([]string{strings.TrimPrefix(f.Tag, "#")})
		args = append(args, string(tagJSON))
		conds = append(conds, fmt.Sprintf("tags @> $%d::jsonb", len(args)))
	}
	limit := f.Limit
	if limit <= 0 || limit > 300 {
		limit = 100
	}
	args = append(args, limit)
	query := `select id, kind, title, content, tags, completed, pinned, source, doc_path, card_json, created_at, updated_at
		from pace_entries where ` + strings.Join(conds, " and ") +
		fmt.Sprintf(` order by pinned desc, created_at desc limit $%d`, len(args))

	rows, err := p.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]entryItem, 0)
	for rows.Next() {
		var item entryItem
		var tagsRaw []byte
		var cardRaw []byte
		if err := rows.Scan(
			&item.ID, &item.Kind, &item.Title, &item.Content, &tagsRaw,
			&item.Completed, &item.Pinned, &item.Source, &item.DocPath,
			&cardRaw, &item.CreatedAt, &item.UpdatedAt,
		); err != nil {
			return nil, err
		}
		if len(tagsRaw) > 0 {
			_ = json.Unmarshal(tagsRaw, &item.Tags)
		}
		if item.Tags == nil {
			item.Tags = []string{}
		}
		if len(cardRaw) > 0 && string(cardRaw) != "null" {
			item.CardJSON = cardRaw
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

func (p *poolStore) Create(ctx context.Context, item entryItem) (entryItem, error) {
	tagsRaw, _ := json.Marshal(item.Tags)
	var cardArg any
	if len(item.CardJSON) > 0 {
		cardArg = string(item.CardJSON)
	}
	var outTags []byte
	var outCard []byte
	err := p.pool.QueryRow(
		ctx,
		`insert into pace_entries (id, kind, title, content, tags, completed, pinned, source, doc_path, card_json)
		 values ($1, $2, $3, $4, $5::jsonb, $6, $7, $8, $9, $10::jsonb)
		 returning id, kind, title, content, tags, completed, pinned, source, doc_path, card_json, created_at, updated_at`,
		item.ID, item.Kind, item.Title, item.Content, string(tagsRaw),
		item.Completed, item.Pinned, item.Source, item.DocPath, cardArg,
	).Scan(
		&item.ID, &item.Kind, &item.Title, &item.Content, &outTags,
		&item.Completed, &item.Pinned, &item.Source, &item.DocPath,
		&outCard, &item.CreatedAt, &item.UpdatedAt,
	)
	if err != nil {
		return entryItem{}, err
	}
	_ = json.Unmarshal(outTags, &item.Tags)
	if item.Tags == nil {
		item.Tags = []string{}
	}
	if len(outCard) > 0 && string(outCard) != "null" {
		item.CardJSON = outCard
	}
	return item, nil
}

func (p *poolStore) Update(ctx context.Context, id string, req updateEntryRequest) (entryItem, error) {
	var current entryItem
	var tagsRaw []byte
	var cardRaw []byte
	err := p.pool.QueryRow(
		ctx,
		`select id, kind, title, content, tags, completed, pinned, source, doc_path, card_json, created_at, updated_at
		 from pace_entries where id = $1`,
		id,
	).Scan(
		&current.ID, &current.Kind, &current.Title, &current.Content, &tagsRaw,
		&current.Completed, &current.Pinned, &current.Source, &current.DocPath,
		&cardRaw, &current.CreatedAt, &current.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return entryItem{}, errEntryNotFound
	}
	if err != nil {
		return entryItem{}, err
	}
	_ = json.Unmarshal(tagsRaw, &current.Tags)
	if len(cardRaw) > 0 && string(cardRaw) != "null" {
		current.CardJSON = cardRaw
	}
	if req.Kind != nil {
		current.Kind = normalizeKind(*req.Kind)
	}
	if req.Title != nil {
		current.Title = strings.TrimSpace(*req.Title)
	}
	if req.Content != nil {
		current.Content = strings.TrimSpace(*req.Content)
	}
	if req.Tags != nil {
		current.Tags = normalizeTags(req.Tags)
	}
	if req.Completed != nil {
		current.Completed = *req.Completed
	}
	if req.Pinned != nil {
		current.Pinned = *req.Pinned
	}
	nextTagsRaw, _ := json.Marshal(current.Tags)
	err = p.pool.QueryRow(
		ctx,
		`update pace_entries
		 set kind=$2, title=$3, content=$4, tags=$5::jsonb, completed=$6, pinned=$7, updated_at=now()
		 where id=$1
		 returning updated_at`,
		id, current.Kind, current.Title, current.Content, string(nextTagsRaw), current.Completed, current.Pinned,
	).Scan(&current.UpdatedAt)
	if err != nil {
		return entryItem{}, err
	}
	return current, nil
}

func (p *poolStore) Delete(ctx context.Context, id string) error {
	tag, err := p.pool.Exec(ctx, `delete from pace_entries where id = $1`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return errEntryNotFound
	}
	return nil
}

func main() {
	ctx := context.Background()
	ttlDays := 90
	if raw := strings.TrimSpace(os.Getenv("TOKEN_TTL_DAYS")); raw != "" {
		if n, err := strconv.Atoi(raw); err == nil && n > 0 {
			ttlDays = n
		}
	}

	hermesBase := os.Getenv("HERMES_BASE_URL")
	if strings.TrimSpace(hermesBase) == "" {
		hermesBase = "http://host.docker.internal:8642"
	}
	hermesKey := os.Getenv("HERMES_API_KEY")
	if strings.TrimSpace(hermesKey) == "" {
		log.Fatal("HERMES_API_KEY is required")
	}

	auth := authConfig{
		Username:       os.Getenv("AUTH_USERNAME"),
		DisplayName:    os.Getenv("AUTH_DISPLAY_NAME"),
		Password:       os.Getenv("AUTH_PASSWORD"),
		PasswordSHA256: os.Getenv("AUTH_PASSWORD_SHA256"),
		SigningSecret:  []byte(os.Getenv("AUTH_SIGNING_SECRET")),
		TokenTTL:       time.Duration(ttlDays) * 24 * time.Hour,
		HermesAPIKey:   hermesKey,
	}
	if err := auth.validate(); err != nil {
		log.Fatal(err)
	}

	var entries entryStore
	dsn := buildDSN()
	if dsn != "" {
		pool, err := pgxpool.New(ctx, dsn)
		if err != nil {
			log.Fatal(err)
		}
		defer pool.Close()
		if err := pool.Ping(ctx); err != nil {
			log.Fatal(err)
		}
		if err := migrate(ctx, pool); err != nil {
			log.Fatal(err)
		}
		entries = &poolStore{pool: pool}
	} else {
		log.Printf("WARNING: DATABASE_* not set; entries endpoints will return 503")
	}

	apkDir := os.Getenv("APP_RELEASE_DIR")
	if strings.TrimSpace(apkDir) == "" {
		apkDir = "/data/apks"
	}

	s := &server{
		auth:    auth,
		hermes:  newHermesClient(hermesBase, hermesKey),
		entries: entries,
		docs:    newDocStore(os.Getenv("ASSISTANT_DATA_DIR"), os.Getenv("HERMES_MEMORY_FILE")),
		apkDir:  apkDir,
	}

	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	log.Printf("PaceApp API listening on %s (hermes=%s)", addr, hermesBase)
	httpServer := &http.Server{
		Addr:              addr,
		Handler:           logging(s.routes()),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	log.Fatal(httpServer.ListenAndServe())
}

func buildDSN() string {
	if dsn := strings.TrimSpace(os.Getenv("DATABASE_URL")); dsn != "" {
		return dsn
	}
	host := strings.TrimSpace(os.Getenv("DATABASE_HOST"))
	port := strings.TrimSpace(os.Getenv("DATABASE_PORT"))
	name := strings.TrimSpace(os.Getenv("DATABASE_NAME"))
	user := strings.TrimSpace(os.Getenv("DATABASE_USER"))
	pass := os.Getenv("DATABASE_PASSWORD")
	if host == "" || port == "" || name == "" || user == "" || pass == "" {
		return ""
	}
	return fmt.Sprintf("postgres://%s:%s@%s:%s/%s?sslmode=disable",
		url.QueryEscape(user), url.QueryEscape(pass), host, port, name)
}

func migrationNumber(name string) int {
	m := migrationNumberPattern.FindStringSubmatch(name)
	if m == nil {
		return -1
	}
	n, err := strconv.Atoi(m[1])
	if err != nil {
		return -1
	}
	return n
}

func migrate(ctx context.Context, db *pgxpool.Pool) error {
	if _, err := db.Exec(ctx, `create table if not exists schema_migrations (name text primary key, applied_at timestamptz not null default now())`); err != nil {
		return err
	}
	entries, err := fs.ReadDir(migrationFiles, "migrations")
	if err != nil {
		return err
	}
	applied := map[int]bool{}
	rows, err := db.Query(ctx, `select name from schema_migrations`)
	if err != nil {
		return err
	}
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			rows.Close()
			return err
		}
		if n := migrationNumber(name); n >= 0 {
			applied[n] = true
		}
	}
	rows.Close()
	var names []string
	for _, e := range entries {
		if !e.IsDir() && migrationNumber(e.Name()) >= 0 {
			names = append(names, e.Name())
		}
	}
	sort.Slice(names, func(i, j int) bool { return migrationNumber(names[i]) < migrationNumber(names[j]) })
	for _, name := range names {
		n := migrationNumber(name)
		if applied[n] {
			continue
		}
		data, err := migrationFiles.ReadFile(path.Join("migrations", name))
		if err != nil {
			return err
		}
		tx, err := db.Begin(ctx)
		if err != nil {
			return err
		}
		if _, err = tx.Exec(ctx, string(data)); err == nil {
			_, err = tx.Exec(ctx, `insert into schema_migrations(name) values($1)`, name)
		}
		if err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("migration %s: %w", name, err)
		}
		if err = tx.Commit(ctx); err != nil {
			return err
		}
		applied[n] = true
	}
	return nil
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/health", s.health)
	mux.HandleFunc("POST /api/v1/auth/login", s.login)
	mux.Handle("POST /api/v1/auth/logout", s.authMiddleware(http.HandlerFunc(s.logout)))
	mux.Handle("GET /api/v1/me", s.authMiddleware(http.HandlerFunc(s.me)))

	mux.Handle("GET /api/v1/health/hermes", s.authMiddleware(http.HandlerFunc(s.hermesHealth)))
	mux.Handle("GET /api/v1/sessions", s.authMiddleware(http.HandlerFunc(s.listSessions)))
	mux.Handle("POST /api/v1/sessions", s.authMiddleware(http.HandlerFunc(s.createSession)))
	mux.Handle("PATCH /api/v1/sessions/{id}", s.authMiddleware(http.HandlerFunc(s.updateSession)))
	mux.Handle("DELETE /api/v1/sessions/{id}", s.authMiddleware(http.HandlerFunc(s.deleteSession)))
	mux.Handle("GET /api/v1/sessions/{id}/messages", s.authMiddleware(http.HandlerFunc(s.sessionMessages)))
	mux.Handle("POST /api/v1/sessions/{id}/chat", s.authMiddleware(http.HandlerFunc(s.sessionChat)))
	mux.Handle("POST /api/v1/sessions/{id}/chat/stream", s.authMiddleware(http.HandlerFunc(s.sessionChatStream)))
	mux.Handle("POST /api/v1/runs/{id}/approval", s.authMiddleware(http.HandlerFunc(s.runApproval)))
	mux.Handle("POST /api/v1/runs/{id}/stop", s.authMiddleware(http.HandlerFunc(s.runStop)))

	mux.Handle("GET /api/v1/entries", s.authMiddleware(http.HandlerFunc(s.listEntries)))
	mux.Handle("POST /api/v1/entries", s.authMiddleware(http.HandlerFunc(s.createEntry)))
	mux.Handle("PATCH /api/v1/entries/{id}", s.authMiddleware(http.HandlerFunc(s.updateEntry)))
	mux.Handle("DELETE /api/v1/entries/{id}", s.authMiddleware(http.HandlerFunc(s.deleteEntry)))

	mux.Handle("GET /api/v1/docs", s.authMiddleware(http.HandlerFunc(s.listDocs)))
	mux.Handle("GET /api/v1/docs/content", s.authMiddleware(http.HandlerFunc(s.getDocContent)))
	mux.Handle("PUT /api/v1/docs/content", s.authMiddleware(http.HandlerFunc(s.putDocContent)))

	mux.Handle("GET /api/v1/app/latest", s.authMiddleware(http.HandlerFunc(s.latestApp)))
	mux.Handle("GET /api/v1/app/apk", s.authMiddleware(http.HandlerFunc(s.appAPK)))
	return mux
}

func (s *server) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *server) login(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if !s.auth.checkCredentials(req.Username, req.Password) {
		errorJSON(w, http.StatusUnauthorized, "invalid_credentials")
		return
	}
	token, exp, err := s.auth.issueToken(time.Now())
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "session_failed")
		return
	}
	writeJSON(w, http.StatusOK, authResponse{
		Token:     token,
		ExpiresAt: exp.UTC().Format(time.RFC3339),
		User: userJSON{
			Username:    strings.TrimSpace(s.auth.Username),
			DisplayName: s.auth.displayName(),
		},
	})
}

func (s *server) logout(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) me(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, userJSON{
		Username:    strings.TrimSpace(s.auth.Username),
		DisplayName: s.auth.displayName(),
	})
}

func (s *server) latestApp(w http.ResponseWriter, r *http.Request) {
	raw, err := os.ReadFile(filepath.Join(s.apkDir, "latest.json"))
	if err != nil {
		errorJSON(w, http.StatusNotFound, "release_not_found")
		return
	}
	var rel appReleaseJSON
	if err := json.Unmarshal(raw, &rel); err != nil {
		errorJSON(w, http.StatusInternalServerError, "release_not_found")
		return
	}
	writeJSON(w, http.StatusOK, rel)
}

func (s *server) appAPK(w http.ResponseWriter, r *http.Request) {
	file, err := os.Open(filepath.Join(s.apkDir, "paceapp.apk"))
	if err != nil {
		errorJSON(w, http.StatusNotFound, "release_not_found")
		return
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "release_not_found")
		return
	}
	_ = http.NewResponseController(w).SetWriteDeadline(time.Time{})
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	http.ServeContent(w, r, "paceapp.apk", info.ModTime(), file)
}

func decodeJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	defer r.Body.Close()
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		errorJSON(w, http.StatusBadRequest, "invalid_json")
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func errorJSON(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]string{"error": code})
}

func logging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Printf("%s %s %s", r.Method, r.URL.Path, time.Since(start))
	})
}
