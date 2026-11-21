package main

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
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

func TestOfficeDocsRawAndKnowledgeGraph(t *testing.T) {
	srv, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {})
	token, _, _ := srv.auth.issueToken(time.Now())
	api := httptest.NewServer(srv.routes())
	defer api.Close()

	// Seed a markdown note with hashtag + wiki link and an entry linking to it
	_ = os.MkdirAll(filepath.Join(srv.docs.assistantDir, "notes"), 0o755)
	if err := os.WriteFile(filepath.Join(srv.docs.assistantDir, "notes", "plan.md"), []byte("# 年度计划\n\n坚持 #跑步 与阅读，参考 [[honors]]"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(srv.docs.assistantDir, "notes", "honors.md"), []byte("# 荣誉记录\n\n#跑步 10公里完赛"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(srv.docs.memoryFile, []byte("# 记忆\n\n## § 跑步与习惯\n每周保持三次跑步。\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	_, _ = srv.entries.Create(context.Background(), entryItem{
		ID:      "entry-graph-1",
		Kind:    "note",
		Title:   "晨跑打卡",
		Content: "今天跑了 6 公里 #跑步",
		Tags:    []string{"跑步"},
		Source:  "paceapp",
	})

	// Create minimal valid DOCX (word/document.xml) and upload via /api/v1/uploads
	var docxBuf bytes.Buffer
	zw := newTestZip(&docxBuf)
	addZipFile(t, zw, "word/document.xml", `<w:document><w:body><w:p><w:r><w:t>季度工作汇报</w:t></w:r></w:p></w:body></w:document>`)
	_ = zw.Close()

	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	part, _ := mw.CreateFormFile("file", "report.docx")
	_, _ = part.Write(docxBuf.Bytes())
	_ = mw.Close()

	upReq, _ := http.NewRequest(http.MethodPost, api.URL+"/api/v1/uploads", &body)
	upReq.Header.Set("Authorization", "Bearer "+token)
	upReq.Header.Set("Content-Type", mw.FormDataContentType())
	upResp, err := http.DefaultClient.Do(upReq)
	if err != nil {
		t.Fatal(err)
	}
	var upOut map[string]any
	if err := json.NewDecoder(upResp.Body).Decode(&upOut); err != nil {
		t.Fatal(err)
	}
	upResp.Body.Close()
	if upResp.StatusCode != http.StatusCreated {
		t.Fatalf("expected 201 docx upload, got %d", upResp.StatusCode)
	}
	extracted, _ := upOut["extracted_text"].(string)
	if !strings.Contains(extracted, "季度工作汇报") {
		t.Fatalf("expected extracted docx text, got %q", extracted)
	}
	docxRel, _ := upOut["path"].(string)

	// Verify /api/v1/docs/content reads extracted docx text and /api/v1/docs/raw serves exact bytes
	docReq, _ := http.NewRequest(http.MethodGet, api.URL+"/api/v1/docs/content?path="+docxRel, nil)
	docReq.Header.Set("Authorization", "Bearer "+token)
	docResp, err := http.DefaultClient.Do(docReq)
	if err != nil {
		t.Fatal(err)
	}
	var docDetail docContentResponse
	_ = json.NewDecoder(docResp.Body).Decode(&docDetail)
	docResp.Body.Close()
	if !docDetail.RawAvailable || !strings.Contains(docDetail.Content, "季度工作汇报") {
		t.Fatalf("unexpected docx doc detail: %+v", docDetail)
	}

	rawReq, _ := http.NewRequest(http.MethodGet, api.URL+"/api/v1/docs/raw?path="+docxRel, nil)
	rawReq.Header.Set("Authorization", "Bearer "+token)
	rawResp, err := http.DefaultClient.Do(rawReq)
	if err != nil {
		t.Fatal(err)
	}
	rawResp.Body.Close()
	if rawResp.StatusCode != http.StatusOK || !strings.Contains(rawResp.Header.Get("Content-Type"), "wordprocessingml") {
		t.Fatalf("unexpected raw response status=%d ct=%s", rawResp.StatusCode, rawResp.Header.Get("Content-Type"))
	}

	// Verify path traversal blocked on /api/v1/docs/raw
	badRawReq, _ := http.NewRequest(http.MethodGet, api.URL+"/api/v1/docs/raw?path=../secret.txt", nil)
	badRawReq.Header.Set("Authorization", "Bearer "+token)
	badRawResp, err := http.DefaultClient.Do(badRawReq)
	if err != nil {
		t.Fatal(err)
	}
	badRawResp.Body.Close()
	if badRawResp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400 on raw path traversal, got %d", badRawResp.StatusCode)
	}

	// Verify /api/v1/graph builds hubs, memory sections, docs, tags, entries, and edges
	gReq, _ := http.NewRequest(http.MethodGet, api.URL+"/api/v1/graph", nil)
	gReq.Header.Set("Authorization", "Bearer "+token)
	gResp, err := http.DefaultClient.Do(gReq)
	if err != nil {
		t.Fatal(err)
	}
	defer gResp.Body.Close()
	if gResp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 graph, got %d", gResp.StatusCode)
	}
	var graph knowledgeGraphResponse
	if err := json.NewDecoder(gResp.Body).Decode(&graph); err != nil {
		t.Fatal(err)
	}
	if graph.Stats.NodeCount < 8 || graph.Stats.EdgeCount < 7 || graph.Stats.TagCount < 1 {
		t.Fatalf("expected rich graph stats, got %+v", graph.Stats)
	}

	// Verify XLSX extraction as well
	xlsxPath := filepath.Join(srv.docs.assistantDir, "uploads", "budget.xlsx")
	_ = os.MkdirAll(filepath.Dir(xlsxPath), 0o755)
	var xBuf bytes.Buffer
	xzw := newTestZip(&xBuf)
	addZipFile(t, xzw, "xl/sharedStrings.xml", `<sst><si><t>项目</t></si><si><t>预算</t></si></sst>`)
	addZipFile(t, xzw, "xl/worksheets/sheet1.xml", `<worksheet><sheetData><row><c t="s"><v>0</v></c><c t="s"><v>1</v></c></row><row><c><v>100</v></c><c><v>200</v></c></row></sheetData></worksheet>`)
	_ = xzw.Close()
	if err := os.WriteFile(xlsxPath, xBuf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	xDoc, err := srv.docs.read("uploads/budget.xlsx")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(xDoc.Content, "| 项目 | 预算 |") || !strings.Contains(xDoc.Content, "| 100 | 200 |") {
		t.Fatalf("unexpected xlsx extracted table: %s", xDoc.Content)
	}

	// Verify Hermes-generated exports/*.docx is classified under upload and serves extracted + raw binary
	exportDocxPath := filepath.Join(srv.docs.assistantDir, "exports", "运动汇报.docx")
	_ = os.MkdirAll(filepath.Dir(exportDocxPath), 0o755)
	if err := os.WriteFile(exportDocxPath, docxBuf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	listed, err := srv.docs.list()
	if err != nil {
		t.Fatal(err)
	}
	var foundExport bool
	for _, it := range listed {
		if it.Path == "exports/运动汇报.docx" && it.Category == "upload" {
			foundExport = true
			break
		}
	}
	if !foundExport {
		t.Fatalf("expected exports/运动汇报.docx under category upload, got %+v", listed)
	}
	exportContentReq, _ := http.NewRequest(http.MethodGet, api.URL+"/api/v1/docs/content?path=exports/%E8%BF%90%E5%8A%A8%E6%B1%87%E6%8A%A5.docx", nil)
	exportContentReq.Header.Set("Authorization", "Bearer "+token)
	exportContentResp, err := http.DefaultClient.Do(exportContentReq)
	if err != nil {
		t.Fatal(err)
	}
	defer exportContentResp.Body.Close()
	if exportContentResp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 on exports content, got %d", exportContentResp.StatusCode)
	}
	exportRawReq, _ := http.NewRequest(http.MethodGet, api.URL+"/api/v1/docs/raw?path=exports/%E8%BF%90%E5%8A%A8%E6%B1%87%E6%8A%A5.docx", nil)
	exportRawReq.Header.Set("Authorization", "Bearer "+token)
	exportRawResp, err := http.DefaultClient.Do(exportRawReq)
	if err != nil {
		t.Fatal(err)
	}
	exportRawBytes, _ := io.ReadAll(exportRawResp.Body)
	exportRawResp.Body.Close()
	if exportRawResp.StatusCode != http.StatusOK || !bytes.Equal(exportRawBytes, docxBuf.Bytes()) {
		t.Fatalf("expected raw export docx bytes match, status=%d len=%d", exportRawResp.StatusCode, len(exportRawBytes))
	}
}

func TestSttTranscribe(t *testing.T) {
	sf := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/audio/transcriptions" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		if err := r.ParseMultipartForm(4 << 20); err != nil {
			t.Fatal(err)
		}
		if got := r.FormValue("model"); got != "FunAudioLLM/SenseVoiceSmall" {
			t.Fatalf("unexpected model: %q", got)
		}
		_, _ = w.Write([]byte(`{"text":"<|zh|><|NEUTRAL|><|Speech|><|withitn|>帮我让河马斯整理一下日记"}`))
	}))
	defer sf.Close()

	llmCalled := false
	llm := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		llmCalled = true
		if r.URL.Path != "/v1/chat/completions" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"should not be called"}}]}`))
	}))
	defer llm.Close()

	srv, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {})
	srv.stt = newSTTClient(sf.URL, "sf-test-key", "")
	srv.llm = newLLMClient(llm.URL, "ds-test-key", "deepseek-chat")
	token, _, _ := srv.auth.issueToken(time.Now())

	api := httptest.NewServer(srv.routes())
	defer api.Close()

	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	_ = mw.WriteField("context", "")
	part, _ := mw.CreateFormFile("audio", "voice.wav")
	_, _ = part.Write(bytes.Repeat([]byte{0x12, 0x34}, 1200))
	_ = mw.Close()

	req, _ := http.NewRequest(http.MethodPost, api.URL+"/api/v1/stt/transcribe", &body)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 transcribe, got %d", resp.StatusCode)
	}
	var out struct {
		Text     string `json:"text"`
		RawText  string `json:"raw_text"`
		Refined  bool   `json:"refined"`
		Provider string `json:"provider"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	if llmCalled {
		t.Fatal("expected /api/v1/stt/transcribe to bypass DeepSeek LLM refine")
	}
	if out.Text != "帮我让河马斯整理一下日记" || out.RawText != "帮我让河马斯整理一下日记" {
		t.Fatalf("expected direct ASR text, got %+v", out)
	}
	if out.Refined || out.Provider != "siliconflow" {
		t.Fatalf("unexpected transcribe response: %+v", out)
	}
}

func TestAnalyticsDashboardAndMutations(t *testing.T) {
	var seenListSource string
	srv, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet && r.URL.Path == "/api/sessions" {
			seenListSource = r.URL.Query().Get("source")
			_, _ = w.Write([]byte(`{"data":[{"id":"s1","title":"Demo","source":"api_server","message_count":2}]}`))
			return
		}
		w.WriteHeader(http.StatusNotFound)
	})
	token, _, _ := srv.auth.issueToken(time.Now())
	api := httptest.NewServer(srv.routes())
	defer api.Close()

	// Seed Hermes's exact lab/sessions.json schema
	todayStr := time.Now().In(srv.docs.tz).Format("2006-01-02")
	_ = os.MkdirAll(filepath.Join(srv.docs.assistantDir, "lab"), 0o755)
	_ = os.WriteFile(filepath.Join(srv.docs.assistantDir, "lab", "sessions.json"), []byte(fmt.Sprintf(`{
		"note": "实验室学习打卡记录",
		"sessions": [
			{"date": "%s", "arrive": "%s 10:03", "leave": "%s 12:11", "minutes": 128}
		]
	}`, todayStr, todayStr, todayStr)), 0o644)

	// Seed running image + notes.json + custom metric
	runDir := filepath.Join(srv.docs.assistantDir, "images", "running")
	_ = os.MkdirAll(runDir, 0o755)
	_ = os.WriteFile(filepath.Join(runDir, "跑步_11-19.jpg"), []byte("fakejpg"), 0o644)
	_ = os.WriteFile(filepath.Join(runDir, "notes.json"), []byte(`{"11-19":["简单慢跑3.75km"]}`), 0o644)

	_ = os.MkdirAll(filepath.Join(srv.docs.assistantDir, "metrics"), 0o755)
	_ = os.WriteFile(filepath.Join(srv.docs.assistantDir, "metrics", "reading.json"), []byte(fmt.Sprintf(`{
		"id":"reading","name":"论文阅读","unit":"篇","color":"#6D28D9","goal":2,
		"records":[{"date":"%s","value":2,"note":"精读两篇"}]
	}`, todayStr)), 0o644)

	doJSON := func(method, url, payload string) *http.Response {
		req, _ := http.NewRequest(method, url, strings.NewReader(payload))
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Content-Type", "application/json")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		return resp
	}

	// Verify GET /api/v1/sessions?source=paceapp forwards source=api_server upstream
	sessResp := doJSON(http.MethodGet, api.URL+"/api/v1/sessions?source=paceapp", "")
	sessResp.Body.Close()
	if sessResp.StatusCode != http.StatusOK || seenListSource != "api_server" {
		t.Fatalf("expected source=paceapp -> api_server, got status=%d seen=%q", sessResp.StatusCode, seenListSource)
	}

	// Verify initial analytics read (sleep absent => has_data false, lab=128m, running=1)
	r1 := doJSON(http.MethodGet, api.URL+"/api/v1/analytics?range=7d", "")
	var dash1 analyticsDashboardResponse
	_ = json.NewDecoder(r1.Body).Decode(&dash1)
	r1.Body.Close()
	if r1.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 analytics, got %d", r1.StatusCode)
	}
	if dash1.Lab.TodayMinutes != 128 || dash1.Lab.SessionCount != 1 {
		t.Fatalf("unexpected lab metrics: %+v", dash1.Lab)
	}
	if dash1.Sleep.HasData {
		t.Fatalf("expected sleep.has_data=false when sleep/records.json missing")
	}
	if dash1.Running.TotalRuns != 1 || dash1.Running.TotalDistanceKm != 3.75 {
		t.Fatalf("unexpected running metrics: %+v", dash1.Running)
	}
	if len(dash1.CustomMetrics) != 1 || dash1.CustomMetrics[0].LatestValue != 2 {
		t.Fatalf("unexpected custom metrics: %+v", dash1.CustomMetrics)
	}

	// Verify sleep upsert -> has_data=true
	sr := doJSON(http.MethodPost, api.URL+"/api/v1/analytics/sleep", fmt.Sprintf(`{"date":"%s","bedtime":"23:30","wake_time":"07:20","score":88,"note":"睡得不错"}`, todayStr))
	sr.Body.Close()
	if sr.StatusCode != http.StatusCreated {
		t.Fatalf("expected 201 sleep upsert, got %d", sr.StatusCode)
	}

	// Verify lab checkin -> duplicate 409 -> checkout -> manual backfill -> delete
	c1 := doJSON(http.MethodPost, api.URL+"/api/v1/analytics/lab/checkin", `{"note":"下午实验"}`)
	c1.Body.Close()
	if c1.StatusCode != http.StatusCreated {
		t.Fatalf("expected 201 checkin, got %d", c1.StatusCode)
	}
	cDup := doJSON(http.MethodPost, api.URL+"/api/v1/analytics/lab/checkin", `{}`)
	cDup.Body.Close()
	if cDup.StatusCode != http.StatusConflict {
		t.Fatalf("expected 409 duplicate checkin, got %d", cDup.StatusCode)
	}
	co := doJSON(http.MethodPost, api.URL+"/api/v1/analytics/lab/checkout", `{}`)
	co.Body.Close()
	if co.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 checkout, got %d", co.StatusCode)
	}

	mResp := doJSON(http.MethodPost, api.URL+"/api/v1/analytics/lab/sessions", fmt.Sprintf(`{"date":"%s","arrive":"19:00","leave":"21:30","note":"补录晚间"}`, todayStr))
	var created labSessionItem
	_ = json.NewDecoder(mResp.Body).Decode(&created)
	mResp.Body.Close()
	if mResp.StatusCode != http.StatusCreated || created.Minutes == nil || *created.Minutes != 150 {
		t.Fatalf("expected 150m manual backfill, status=%d item=%+v", mResp.StatusCode, created)
	}

	delResp := doJSON(http.MethodDelete, api.URL+"/api/v1/analytics/lab/sessions?arrive="+strings.ReplaceAll(created.Arrive, " ", "%20"), "")
	delResp.Body.Close()
	if delResp.StatusCode != http.StatusNoContent {
		t.Fatalf("expected 204 delete session, got %d", delResp.StatusCode)
	}

	r2 := doJSON(http.MethodGet, api.URL+"/api/v1/analytics?range=30d", "")
	var dash2 analyticsDashboardResponse
	_ = json.NewDecoder(r2.Body).Decode(&dash2)
	r2.Body.Close()
	if !dash2.Sleep.HasData || dash2.Sleep.LatestDurationMinutes != 470 {
		t.Fatalf("expected sleep duration 470 min (23:30->07:20), got %+v", dash2.Sleep)
	}

	// Verify structured running/records.json merge preserves image_path & notes.json note when note is omitted
	runYear := time.Now().In(srv.docs.tz).Year()
	_ = os.MkdirAll(filepath.Join(srv.docs.assistantDir, "running"), 0o755)
	_ = os.WriteFile(filepath.Join(srv.docs.assistantDir, "running", "records.json"), []byte(fmt.Sprintf(`{
		"records": [
			{"date": "%d-11-19", "distance_km": 3.75, "duration_minutes": 30, "pace": "7'52\"/km", "source": "keep"},
			{"date": "%d-11-20", "distance_km": 3.68, "duration_minutes": 34, "pace": "9'07\"/km", "source": "coros", "note": "慢跑恢复"}
		]
	}`, runYear, runYear)), 0o644)

	r3 := doJSON(http.MethodGet, api.URL+"/api/v1/analytics?range=30d", "")
	var dash3 analyticsDashboardResponse
	_ = json.NewDecoder(r3.Body).Decode(&dash3)
	r3.Body.Close()
	if dash3.Running.TotalRuns != 2 || dash3.Running.TotalDistanceKm != 7.43 {
		t.Fatalf("expected 2 runs totaling 7.43 km, got %+v", dash3.Running)
	}
	var merged19 *runningRecordItem
	for i := range dash3.Running.RecentRuns {
		if dash3.Running.RecentRuns[i].Date == fmt.Sprintf("%d-11-19", runYear) {
			merged19 = &dash3.Running.RecentRuns[i]
			break
		}
	}
	if merged19 == nil || merged19.ImagePath != "images/running/跑步_11-19.jpg" || merged19.Note != "简单慢跑3.75km" {
		t.Fatalf("expected merged 11-19 to preserve image_path and note, got %+v", merged19)
	}
}

func newTestZip(buf *bytes.Buffer) *zip.Writer {
	return zip.NewWriter(buf)
}

func addZipFile(t *testing.T, zw *zip.Writer, name, content string) {
	t.Helper()
	w, err := zw.Create(name)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := w.Write([]byte(content)); err != nil {
		t.Fatal(err)
	}
}
