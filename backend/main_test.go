package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func newTestServer(t *testing.T, hermesHandler http.HandlerFunc) (*server, *httptest.Server) {
	t.Helper()
	upstream := httptest.NewServer(hermesHandler)
	t.Cleanup(upstream.Close)

	apkDir := t.TempDir()
	sum := sha256.Sum256([]byte("test-password"))
	srv := &server{
		auth: authConfig{
			Username:       "dutu",
			DisplayName:    "Dutu",
			PasswordSHA256: hex.EncodeToString(sum[:]),
			SigningSecret:  []byte("0123456789abcdef0123456789abcdef"),
			TokenTTL:       24 * time.Hour,
		},
		hermes: newHermesClient(upstream.URL, "hermes-secret-key"),
		apkDir: apkDir,
	}
	return srv, upstream
}

func TestAuthAndHermesProxy(t *testing.T) {
	var seenAuth string
	var createdSource string
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

	// Bad credentials rejected
	badResp, err := http.Post(api.URL+"/api/v1/auth/login", "application/json", strings.NewReader(`{"username":"dutu","password":"wrong"}`))
	if err != nil {
		t.Fatal(err)
	}
	badResp.Body.Close()
	if badResp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 for bad password, got %d", badResp.StatusCode)
	}

	// Valid login
	loginResp, err := http.Post(api.URL+"/api/v1/auth/login", "application/json", strings.NewReader(`{"username":"dutu","password":"test-password"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer loginResp.Body.Close()
	if loginResp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 on login, got %d", loginResp.StatusCode)
	}
	var auth authResponse
	if err := json.NewDecoder(loginResp.Body).Decode(&auth); err != nil {
		t.Fatal(err)
	}
	if auth.Token == "" || auth.User.Username != "dutu" {
		t.Fatalf("unexpected auth response: %+v", auth)
	}

	// Unauthenticated request rejected
	unauthResp, err := http.Get(api.URL + "/api/v1/sessions")
	if err != nil {
		t.Fatal(err)
	}
	unauthResp.Body.Close()
	if unauthResp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 without token, got %d", unauthResp.StatusCode)
	}

	// Create session proxies to Hermes with Bearer HERMES_API_KEY
	req, _ := http.NewRequest(http.MethodPost, api.URL+"/api/v1/sessions", strings.NewReader(`{"title":"测试对话"}`))
	req.Header.Set("Authorization", "Bearer "+auth.Token)
	req.Header.Set("Content-Type", "application/json")
	sessResp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	sessResp.Body.Close()
	if sessResp.StatusCode != http.StatusCreated {
		t.Fatalf("expected 201 creating session, got %d", sessResp.StatusCode)
	}
	if seenAuth != "Bearer hermes-secret-key" || createdSource != "paceapp" {
		t.Fatalf("unexpected upstream call auth=%q source=%q", seenAuth, createdSource)
	}

	// Stream chat proxies SSE events cleanly
	streamReq, _ := http.NewRequest(http.MethodPost, api.URL+"/api/v1/sessions/sess_1/chat/stream", strings.NewReader(`{"message":"hi"}`))
	streamReq.Header.Set("Authorization", "Bearer "+auth.Token)
	streamReq.Header.Set("Content-Type", "application/json")
	streamResp, err := http.DefaultClient.Do(streamReq)
	if err != nil {
		t.Fatal(err)
	}
	defer streamResp.Body.Close()
	if streamResp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 on stream, got %d", streamResp.StatusCode)
	}
	var buf bytes.Buffer
	_, _ = buf.ReadFrom(streamResp.Body)
	if !strings.Contains(buf.String(), "assistant.delta") || !strings.Contains(buf.String(), "你好，我在") {
		t.Fatalf("unexpected SSE output: %s", buf.String())
	}
}

func TestAppUpdateEndpoints(t *testing.T) {
	srv, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {})
	if err := os.WriteFile(filepath.Join(srv.apkDir, "latest.json"), []byte(`{"version_code":3,"version_name":"1.0.3","notes":"init","sha256":"abc","size":12}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(srv.apkDir, "paceapp.apk"), []byte("fake-apk-bin"), 0o644); err != nil {
		t.Fatal(err)
	}
	token, _, err := srv.auth.issueToken(time.Now())
	if err != nil {
		t.Fatal(err)
	}

	api := httptest.NewServer(srv.routes())
	defer api.Close()

	req, _ := http.NewRequest(http.MethodGet, api.URL+"/api/v1/app/latest", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 for latest.json, got %d", resp.StatusCode)
	}
	var rel appReleaseJSON
	if err := json.NewDecoder(resp.Body).Decode(&rel); err != nil {
		t.Fatal(err)
	}
	if rel.VersionCode != 3 || rel.VersionName != "1.0.3" {
		t.Fatalf("unexpected release: %+v", rel)
	}

	apkReq, _ := http.NewRequest(http.MethodGet, api.URL+"/api/v1/app/apk", nil)
	apkReq.Header.Set("Authorization", "Bearer "+token)
	apkResp, err := http.DefaultClient.Do(apkReq)
	if err != nil {
		t.Fatal(err)
	}
	defer apkResp.Body.Close()
	if apkResp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 for apk, got %d", apkResp.StatusCode)
	}
}

func TestRejectsUnsafeID(t *testing.T) {
	srv, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {})
	token, _, _ := srv.auth.issueToken(time.Now())
	api := httptest.NewServer(srv.routes())
	defer api.Close()

	req, _ := http.NewRequest(http.MethodGet, api.URL+"/api/v1/sessions/..%2Fetc/messages", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400 for unsafe session id, got %d", resp.StatusCode)
	}
}
