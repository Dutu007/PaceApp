package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

type entryItem struct {
	ID        string          `json:"id"`
	Kind      string          `json:"kind"`
	Title     string          `json:"title"`
	Content   string          `json:"content"`
	Tags      []string        `json:"tags"`
	Completed bool            `json:"completed"`
	Pinned    bool            `json:"pinned"`
	Source    string          `json:"source"`
	DocPath   *string         `json:"doc_path,omitempty"`
	CardJSON  json.RawMessage `json:"card_json,omitempty"`
	CreatedAt time.Time       `json:"created_at"`
	UpdatedAt time.Time       `json:"updated_at"`
}

type createEntryRequest struct {
	Kind     string          `json:"kind"`
	Title    string          `json:"title"`
	Content  string          `json:"content"`
	Tags     []string        `json:"tags"`
	Pinned   bool            `json:"pinned"`
	Source   string          `json:"source"`
	SyncDoc  string          `json:"sync_doc"`
	DocPath  string          `json:"doc_path"`
	CardJSON json.RawMessage `json:"card_json"`
}

type updateEntryRequest struct {
	Kind      *string  `json:"kind"`
	Title     *string  `json:"title"`
	Content   *string  `json:"content"`
	Tags      []string `json:"tags"`
	Completed *bool    `json:"completed"`
	Pinned    *bool    `json:"pinned"`
}

type entryFilter struct {
	Kind   string
	Query  string
	Tag    string
	Pinned *bool
	Limit  int
}

type entryStore interface {
	List(ctx context.Context, f entryFilter) ([]entryItem, error)
	Create(ctx context.Context, item entryItem) (entryItem, error)
	Update(ctx context.Context, id string, req updateEntryRequest) (entryItem, error)
	Delete(ctx context.Context, id string) error
}

var errEntryNotFound = errors.New("entry_not_found")

type pgEntryStore struct {
	db queryExecutor
}

type queryExecutor interface {
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
	Exec(ctx context.Context, sql string, args ...any) (any, error)
}

func normalizeKind(raw string) string {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "todo", "diary", "idea", "card":
		return strings.ToLower(strings.TrimSpace(raw))
	default:
		return "note"
	}
}

func normalizeTags(tags []string) []string {
	seen := map[string]bool{}
	out := make([]string, 0, len(tags))
	for _, t := range tags {
		cleaned := strings.TrimPrefix(strings.TrimSpace(t), "#")
		if cleaned == "" || seen[cleaned] {
			continue
		}
		seen[cleaned] = true
		out = append(out, cleaned)
		if len(out) >= 12 {
			break
		}
	}
	return out
}

func newEntryID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return fmt.Sprintf("ent_%d_%s", time.Now().Unix(), hex.EncodeToString(b))
}

func (s *server) listEntries(w http.ResponseWriter, r *http.Request) {
	if s.entries == nil {
		errorJSON(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	limit := 100
	if raw := strings.TrimSpace(r.URL.Query().Get("limit")); raw != "" {
		if n, err := strconv.Atoi(raw); err == nil && n > 0 && n <= 300 {
			limit = n
		}
	}
	var pinnedPtr *bool
	if raw := strings.TrimSpace(r.URL.Query().Get("pinned")); raw != "" {
		b := raw == "true" || raw == "1"
		pinnedPtr = &b
	}
	items, err := s.entries.List(r.Context(), entryFilter{
		Kind:   strings.TrimSpace(r.URL.Query().Get("kind")),
		Query:  strings.TrimSpace(r.URL.Query().Get("q")),
		Tag:    strings.TrimSpace(r.URL.Query().Get("tag")),
		Pinned: pinnedPtr,
		Limit:  limit,
	})
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "database_error")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

func (s *server) createEntry(w http.ResponseWriter, r *http.Request) {
	if s.entries == nil {
		errorJSON(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	var req createEntryRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	req.Title = strings.TrimSpace(req.Title)
	req.Content = strings.TrimSpace(req.Content)
	if req.Content == "" && len(req.CardJSON) == 0 {
		errorJSON(w, http.StatusBadRequest, "content_required")
		return
	}
	source := strings.TrimSpace(req.Source)
	if source == "" {
		if user, _ := r.Context().Value(userContextKey).(string); user == "hermes" {
			source = "hermes"
		} else {
			source = "app"
		}
	}
	kind := normalizeKind(req.Kind)
	tags := normalizeTags(req.Tags)

	var docPath *string
	if syncTarget := strings.TrimSpace(req.SyncDoc); syncTarget != "" && s.docs != nil {
		writtenPath, err := s.docs.appendEntry(syncTarget, req.DocPath, req.Title, req.Content, tags)
		if err == nil && writtenPath != "" {
			docPath = &writtenPath
		}
	} else if strings.TrimSpace(req.DocPath) != "" {
		p := strings.TrimSpace(req.DocPath)
		docPath = &p
	}

	item := entryItem{
		ID:       newEntryID(),
		Kind:     kind,
		Title:    req.Title,
		Content:  req.Content,
		Tags:     tags,
		Pinned:   req.Pinned,
		Source:   source,
		DocPath:  docPath,
		CardJSON: req.CardJSON,
	}
	created, err := s.entries.Create(r.Context(), item)
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "database_error")
		return
	}
	writeJSON(w, http.StatusCreated, created)
}

func (s *server) updateEntry(w http.ResponseWriter, r *http.Request) {
	if s.entries == nil {
		errorJSON(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	id := r.PathValue("id")
	if !validID(id) {
		errorJSON(w, http.StatusBadRequest, "invalid_entry_id")
		return
	}
	var req updateEntryRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	updated, err := s.entries.Update(r.Context(), id, req)
	if errors.Is(err, errEntryNotFound) {
		errorJSON(w, http.StatusNotFound, "entry_not_found")
		return
	}
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "database_error")
		return
	}
	writeJSON(w, http.StatusOK, updated)
}

func (s *server) deleteEntry(w http.ResponseWriter, r *http.Request) {
	if s.entries == nil {
		errorJSON(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	id := r.PathValue("id")
	if !validID(id) {
		errorJSON(w, http.StatusBadRequest, "invalid_entry_id")
		return
	}
	err := s.entries.Delete(r.Context(), id)
	if errors.Is(err, errEntryNotFound) {
		errorJSON(w, http.StatusNotFound, "entry_not_found")
		return
	}
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "database_error")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
