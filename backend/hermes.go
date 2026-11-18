package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

var safeIDPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,128}$`)

type hermesClient struct {
	baseURL string
	apiKey  string
	http    *http.Client
	stream  *http.Client
}

func newHermesClient(baseURL, apiKey string) *hermesClient {
	trimmed := strings.TrimRight(strings.TrimSpace(baseURL), "/")
	return &hermesClient{
		baseURL: trimmed,
		apiKey:  strings.TrimSpace(apiKey),
		http: &http.Client{
			Timeout: 120 * time.Second,
		},
		stream: &http.Client{
			Timeout: 0,
		},
	}
}

func validID(id string) bool {
	return safeIDPattern.MatchString(id) && !strings.Contains(id, "..")
}

func (c *hermesClient) doJSON(ctx context.Context, method, path string, body []byte) (int, []byte, error) {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, reader)
	if err != nil {
		return 0, nil, err
	}
	if c.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.apiKey)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return resp.StatusCode, nil, err
	}
	return resp.StatusCode, data, nil
}

func writeUpstreamJSON(w http.ResponseWriter, status int, data []byte, err error) {
	if err != nil {
		errorJSON(w, http.StatusBadGateway, "hermes_unreachable")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(data)
}

func (s *server) hermesHealth(w http.ResponseWriter, r *http.Request) {
	status, data, err := s.hermes.doJSON(r.Context(), http.MethodGet, "/health", nil)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"status": "unreachable",
			"error":  "hermes_unreachable",
		})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(data)
}

func (s *server) listSessions(w http.ResponseWriter, r *http.Request) {
	q := url.Values{}
	if v := strings.TrimSpace(r.URL.Query().Get("limit")); v != "" {
		q.Set("limit", v)
	} else {
		q.Set("limit", "50")
	}
	if v := strings.TrimSpace(r.URL.Query().Get("offset")); v != "" {
		q.Set("offset", v)
	}
	if v := strings.TrimSpace(r.URL.Query().Get("source")); v != "" {
		q.Set("source", v)
	}
	path := "/api/sessions?" + q.Encode()
	status, data, err := s.hermes.doJSON(r.Context(), http.MethodGet, path, nil)
	writeUpstreamJSON(w, status, data, err)
}

func (s *server) createSession(w http.ResponseWriter, r *http.Request) {
	rawBody, err := readLimitedBody(r, 256<<10)
	if err != nil {
		errorJSON(w, http.StatusBadRequest, "invalid_body")
		return
	}
	payload := map[string]any{}
	if len(bytes.TrimSpace(rawBody)) > 0 {
		if err := json.Unmarshal(rawBody, &payload); err != nil {
			errorJSON(w, http.StatusBadRequest, "invalid_json")
			return
		}
	}
	if _, ok := payload["source"]; !ok {
		payload["source"] = "paceapp"
	}
	encoded, err := json.Marshal(payload)
	if err != nil {
		errorJSON(w, http.StatusBadRequest, "invalid_json")
		return
	}
	status, data, err := s.hermes.doJSON(r.Context(), http.MethodPost, "/api/sessions", encoded)
	writeUpstreamJSON(w, status, data, err)
}

func (s *server) updateSession(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		errorJSON(w, http.StatusBadRequest, "invalid_session_id")
		return
	}
	rawBody, err := readLimitedBody(r, 256<<10)
	if err != nil {
		errorJSON(w, http.StatusBadRequest, "invalid_body")
		return
	}
	status, data, err := s.hermes.doJSON(r.Context(), http.MethodPatch, "/api/sessions/"+url.PathEscape(id), rawBody)
	writeUpstreamJSON(w, status, data, err)
}

func (s *server) deleteSession(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		errorJSON(w, http.StatusBadRequest, "invalid_session_id")
		return
	}
	status, data, err := s.hermes.doJSON(r.Context(), http.MethodDelete, "/api/sessions/"+url.PathEscape(id), nil)
	writeUpstreamJSON(w, status, data, err)
}

func (s *server) sessionMessages(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		errorJSON(w, http.StatusBadRequest, "invalid_session_id")
		return
	}
	q := url.Values{}
	for _, key := range []string{"limit", "offset", "order"} {
		if v := strings.TrimSpace(r.URL.Query().Get(key)); v != "" {
			q.Set(key, v)
		}
	}
	path := "/api/sessions/" + url.PathEscape(id) + "/messages"
	if encoded := q.Encode(); encoded != "" {
		path += "?" + encoded
	}
	status, data, err := s.hermes.doJSON(r.Context(), http.MethodGet, path, nil)
	writeUpstreamJSON(w, status, data, err)
}

func (s *server) sessionChat(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		errorJSON(w, http.StatusBadRequest, "invalid_session_id")
		return
	}
	rawBody, err := readLimitedBody(r, 1<<20)
	if err != nil {
		errorJSON(w, http.StatusBadRequest, "invalid_body")
		return
	}
	status, data, err := s.hermes.doJSON(r.Context(), http.MethodPost, "/api/sessions/"+url.PathEscape(id)+"/chat", rawBody)
	writeUpstreamJSON(w, status, data, err)
}

func (s *server) sessionChatStream(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		errorJSON(w, http.StatusBadRequest, "invalid_session_id")
		return
	}
	rawBody, err := readLimitedBody(r, 1<<20)
	if err != nil {
		errorJSON(w, http.StatusBadRequest, "invalid_body")
		return
	}
	upstreamURL := s.hermes.baseURL + "/api/sessions/" + url.PathEscape(id) + "/chat/stream"
	req, err := http.NewRequestWithContext(r.Context(), http.MethodPost, upstreamURL, bytes.NewReader(rawBody))
	if err != nil {
		errorJSON(w, http.StatusBadGateway, "hermes_unreachable")
		return
	}
	if s.hermes.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+s.hermes.apiKey)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "text/event-stream")

	resp, err := s.hermes.stream.Do(req)
	if err != nil {
		errorJSON(w, http.StatusBadGateway, "hermes_unreachable")
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		errBody, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		writeUpstreamJSON(w, resp.StatusCode, errBody, nil)
		return
	}

	// Disable global write deadline for long-running SSE streams.
	_ = http.NewResponseController(w).SetWriteDeadline(time.Time{})

	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no")
	if sid := resp.Header.Get("X-Hermes-Session-Id"); sid != "" {
		w.Header().Set("X-Hermes-Session-Id", sid)
	}
	w.WriteHeader(http.StatusOK)

	flusher, _ := w.(http.Flusher)
	if flusher != nil {
		flusher.Flush()
	}

	reader := bufio.NewReaderSize(resp.Body, 4096)
	for {
		line, readErr := reader.ReadBytes('\n')
		if len(line) > 0 {
			if _, writeErr := w.Write(line); writeErr != nil {
				return
			}
			if flusher != nil {
				flusher.Flush()
			}
		}
		if readErr != nil {
			if !errors.Is(readErr, io.EOF) {
				return
			}
			return
		}
	}
}

func (s *server) runApproval(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		errorJSON(w, http.StatusBadRequest, "invalid_run_id")
		return
	}
	rawBody, err := readLimitedBody(r, 64<<10)
	if err != nil {
		errorJSON(w, http.StatusBadRequest, "invalid_body")
		return
	}
	status, data, err := s.hermes.doJSON(r.Context(), http.MethodPost, "/v1/runs/"+url.PathEscape(id)+"/approval", rawBody)
	writeUpstreamJSON(w, status, data, err)
}

func (s *server) runStop(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		errorJSON(w, http.StatusBadRequest, "invalid_run_id")
		return
	}
	status, data, err := s.hermes.doJSON(r.Context(), http.MethodPost, "/v1/runs/"+url.PathEscape(id)+"/stop", []byte("{}"))
	writeUpstreamJSON(w, status, data, err)
}

func readLimitedBody(r *http.Request, maxBytes int64) ([]byte, error) {
	defer r.Body.Close()
	return io.ReadAll(io.LimitReader(r.Body, maxBytes))
}
