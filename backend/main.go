package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type server struct {
	auth   authConfig
	hermes *hermesClient
	apkDir string
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

func main() {
	ttlDays := 90
	if raw := strings.TrimSpace(os.Getenv("TOKEN_TTL_DAYS")); raw != "" {
		if n, err := strconv.Atoi(raw); err == nil && n > 0 {
			ttlDays = n
		}
	}

	auth := authConfig{
		Username:       os.Getenv("AUTH_USERNAME"),
		DisplayName:    os.Getenv("AUTH_DISPLAY_NAME"),
		Password:       os.Getenv("AUTH_PASSWORD"),
		PasswordSHA256: os.Getenv("AUTH_PASSWORD_SHA256"),
		SigningSecret:  []byte(os.Getenv("AUTH_SIGNING_SECRET")),
		TokenTTL:       time.Duration(ttlDays) * 24 * time.Hour,
	}
	if err := auth.validate(); err != nil {
		log.Fatal(err)
	}

	hermesBase := os.Getenv("HERMES_BASE_URL")
	if strings.TrimSpace(hermesBase) == "" {
		hermesBase = "http://host.docker.internal:8642"
	}
	hermesKey := os.Getenv("HERMES_API_KEY")
	if strings.TrimSpace(hermesKey) == "" {
		log.Fatal("HERMES_API_KEY is required")
	}

	apkDir := os.Getenv("APP_RELEASE_DIR")
	if strings.TrimSpace(apkDir) == "" {
		apkDir = "/data/apks"
	}

	s := &server{
		auth:   auth,
		hermes: newHermesClient(hermesBase, hermesKey),
		apkDir: apkDir,
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
