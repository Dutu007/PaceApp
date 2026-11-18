package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"
)

type memoryEntryStore struct {
	mu    sync.Mutex
	items map[string]entryItem
}

func newMemoryEntryStore() *memoryEntryStore {
	return &memoryEntryStore{items: map[string]entryItem{}}
}

func (m *memoryEntryStore) List(_ context.Context, f entryFilter) ([]entryItem, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]entryItem, 0, len(m.items))
	for _, it := range m.items {
		if f.Kind != "" && f.Kind != "all" && it.Kind != f.Kind {
			continue
		}
		if f.Pinned != nil && it.Pinned != *f.Pinned {
			continue
		}
		if f.Query != "" && !strings.Contains(strings.ToLower(it.Title+it.Content), strings.ToLower(f.Query)) {
			continue
		}
		out = append(out, it)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.After(out[j].CreatedAt) })
	return out, nil
}

func (m *memoryEntryStore) Create(_ context.Context, item entryItem) (entryItem, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now()
	item.CreatedAt = now
	item.UpdatedAt = now
	if item.Tags == nil {
		item.Tags = []string{}
	}
	m.items[item.ID] = item
	return item, nil
}

func (m *memoryEntryStore) Update(_ context.Context, id string, req updateEntryRequest) (entryItem, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	it, ok := m.items[id]
	if !ok {
		return entryItem{}, errEntryNotFound
	}
	if req.Completed != nil {
		it.Completed = *req.Completed
	}
	if req.Pinned != nil {
		it.Pinned = *req.Pinned
	}
	if req.Content != nil {
		it.Content = strings.TrimSpace(*req.Content)
	}
	it.UpdatedAt = time.Now()
	m.items[id] = it
	return it, nil
}

func (m *memoryEntryStore) Delete(_ context.Context, id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.items[id]; !ok {
		return errEntryNotFound
	}
	delete(m.items, id)
	return nil
}

func newTestServer(t *testing.T, hermesHandler http.HandlerFunc) (*server, *httptest.Server) {
	t.Helper()
	upstream := httptest.NewServer(hermesHandler)
	t.Cleanup(upstream.Close)

	apkDir := t.TempDir()
	assistantDir := t.TempDir()
	memFile := filepath.Join(t.TempDir(), "MEMORY.md")
	_ = os.WriteFile(memFile, []byte("# Memory\n- likes running\n"), 0o644)

	sum := sha256.Sum256([]byte("test-password"))
	srv := &server{
		auth: authConfig{
			Username:       "dutu",
			DisplayName:    "Dutu",
			PasswordSHA256: hex.EncodeToString(sum[:]),
			SigningSecret:  []byte("0123456789abcdef0123456789abcdef"),
			TokenTTL:       24 * time.Hour,
			HermesAPIKey:   "hermes-secret-key",
		},
		hermes:  newHermesClient(upstream.URL, "hermes-secret-key"),
		entries: newMemoryEntryStore(),
		docs:    newDocStore(assistantDir, memFile),
		apkDir:  apkDir,
	}
	return srv, upstream
}

func TestAuthAndHermesProxy(t *testing.T) {
	var seenAuth string
	var createdSource string
	var seenSystem string
	srv, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		seenAuth = r.Header.Get("Authorization")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/health":
			_, _ = w.Write([]byte(`{"status":"ok"}`))
		case r.Method == http.MethodPost && r.URL.Path == "/api/sessions":
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			createdSource, _ = body["source"].(string)
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"object":"hermes.session","session":{"id":"sess_1","title":"Pace","source":"paceapp"}}`))
		case r.Method == http.MethodPost && r.URL.Path == "/api/sessions/sess_1/chat/stream":
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			seenSystem, _ = body["system_message"].(string)
			w.Header().Set("Content-Type", "text/event-stream")
			w.Header().Set("X-Hermes-Session-Id", "sess_1")
			flusher, _ := w.(http.Flusher)
			_, _ = fmt.Fprint(w, "event: assistant.delta\ndata: {\"delta\":\"你好\"}\n\n")
			if flusher != nil {
				flusher.Flush()
			}
			_, _ = fmt.Fprint(w, "event: assistant.completed\ndata: {\"content\":\"你好，我在\"}\n\n")
			_, _ = fmt.Fprint(w, "event: done\ndata: {}\n\n")
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	})

	api := httptest.NewServer(srv.routes())
	defer api.Close()

	badResp, err := http.Post(api.URL+"/api/v1/auth/login", "application/json", strings.NewReader(`{"username":"dutu","password":"wrong"}`))
	if err != nil {
		t.Fatal(err)
	}
	badResp.Body.Close()
	if badResp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 for bad password, got %d", badResp.StatusCode)
	}

	loginResp, err := http.Post(api.URL+"/api/v1/auth/login", "application/json", strings.NewReader(`{"username":"dutu","password":"test-password"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer loginResp.Body.Close()
	var auth authResponse
	if err := json.NewDecoder(loginResp.Body).Decode(&auth); err != nil {
		t.Fatal(err)
	}

	req, _ := http.NewRequest(http.MethodPost, api.URL+"/api/v1/sessions", strings.NewReader(`{"title":"测试对话"}`))
	req.Header.Set("Authorization", "Bearer "+auth.Token)
	req.Header.Set("Content-Type", "application/json")
	sessResp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	sessResp.Body.Close()
	if sessResp.StatusCode != http.StatusCreated || seenAuth != "Bearer hermes-secret-key" || createdSource != "paceapp" {
		t.Fatalf("unexpected upstream call status=%d auth=%q source=%q", sessResp.StatusCode, seenAuth, createdSource)
	}

	streamReq, _ := http.NewRequest(http.MethodPost, api.URL+"/api/v1/sessions/sess_1/chat/stream", strings.NewReader(`{"message":"hi"}`))
	streamReq.Header.Set("Authorization", "Bearer "+auth.Token)
	streamReq.Header.Set("Content-Type", "application/json")
	streamResp, err := http.DefaultClient.Do(streamReq)
	if err != nil {
		t.Fatal(err)
	}
	defer streamResp.Body.Close()
	var buf bytes.Buffer
	_, _ = buf.ReadFrom(streamResp.Body)
	if !strings.Contains(buf.String(), "assistant.delta") || !strings.Contains(seenSystem, "pace-card") {
		t.Fatalf("unexpected SSE output or system_message injection")
	}
}

func TestEntriesAndDocSync(t *testing.T) {
	srv, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {})
	api := httptest.NewServer(srv.routes())
	defer api.Close()

	// Create entry via X-Hermes-Key and sync to diary doc
	req, _ := http.NewRequest(http.MethodPost, api.URL+"/api/v1/entries", strings.NewReader(`{"kind":"note","content":"今天慢跑 5 公里","tags":["跑步"],"sync_doc":"diary"}`))
	req.Header.Set("X-Hermes-Key", "hermes-secret-key")
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("expected 201 creating entry, got %d", resp.StatusCode)
	}
	var created entryItem
	if err := json.NewDecoder(resp.Body).Decode(&created); err != nil {
		t.Fatal(err)
	}
	if created.Source != "hermes" || created.DocPath == nil || !strings.HasPrefix(*created.DocPath, "diary/") {
		t.Fatalf("unexpected created entry: %+v", created)
	}

	// List docs should show both MEMORY.md and synced diary md
	docsReq, _ := http.NewRequest(http.MethodGet, api.URL+"/api/v1/docs", nil)
	docsReq.Header.Set("X-Hermes-Key", "hermes-secret-key")
	docsResp, err := http.DefaultClient.Do(docsReq)
	if err != nil {
		t.Fatal(err)
	}
	defer docsResp.Body.Close()
	var docsPayload struct {
		Items []docSummary `json:"items"`
	}
	if err := json.NewDecoder(docsResp.Body).Decode(&docsPayload); err != nil {
		t.Fatal(err)
	}
	if len(docsPayload.Items) < 2 {
		t.Fatalf("expected at least 2 docs, got %d", len(docsPayload.Items))
	}

	// Path traversal blocked
	badDocReq, _ := http.NewRequest(http.MethodGet, api.URL+"/api/v1/docs/content?path=../etc/passwd", nil)
	badDocReq.Header.Set("X-Hermes-Key", "hermes-secret-key")
	badDocResp, err := http.DefaultClient.Do(badDocReq)
	if err != nil {
		t.Fatal(err)
	}
	badDocResp.Body.Close()
	if badDocResp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400 for path traversal, got %d", badDocResp.StatusCode)
	}
}

func TestSttRefine(t *testing.T) {
	llm := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"帮我让 Hermes 把今天的跑步记录整理一下。"}}]}`))
	}))
	defer llm.Close()

	srv, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {})
	srv.llm = newLLMClient(llm.URL, "test-deepseek-key", "deepseek-chat")
	token, _, _ := srv.auth.issueToken(time.Now())

	api := httptest.NewServer(srv.routes())
	defer api.Close()

	req, _ := http.NewRequest(
		http.MethodPost,
		api.URL+"/api/v1/stt/refine",
		strings.NewReader(`{"text":"帮我让河马斯把今天的跑步记录整理一下","candidates":["帮我让河马斯把今天的跑步记录整理一下"]}`),
	)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out refineSttResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	if !out.Refined || !strings.Contains(out.Text, "Hermes") {
		t.Fatalf("unexpected stt refine output: %+v", out)
	}
}

func TestUploadFile(t *testing.T) {
	srv, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {})
	token, _, _ := srv.auth.issueToken(time.Now())
	api := httptest.NewServer(srv.routes())
	defer api.Close()

	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	part, err := mw.CreateFormFile("file", "notes_test.txt")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = part.Write([]byte("hello pace"))
	_ = mw.Close()

	req, _ := http.NewRequest(http.MethodPost, api.URL+"/api/v1/uploads", &body)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("expected 201 upload, got %d", resp.StatusCode)
	}
	var res map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&res); err != nil {
		t.Fatal(err)
	}
	rel, _ := res["path"].(string)
	if !strings.HasPrefix(rel, "uploads/") {
		t.Fatalf("unexpected upload path: %+v", res)
	}
}
