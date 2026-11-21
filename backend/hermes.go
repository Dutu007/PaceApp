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

const paceSystemPrompt = "你正在通过用户的个人手机应用 PaceApp 与用户对话。\n" +
	"1. 用户的个人记录文档与数据目录位于 /home/dutu/workspace/personal-assistant/data/。PaceApp 的「数据」分析面板直接读取以下单源 JSON 文件：\n" +
	"   - 实验室学习打卡：lab/sessions.json（sessions: [{date, arrive, leave, minutes, note?}]）\n" +
	"   - 睡眠数据：sleep/records.json（records: [{date, bedtime, wake_time, duration_minutes, deep_minutes?, light_minutes?, rem_minutes?, awake_minutes?, score?, source, note?}]）\n" +
	"   - 跑步运动：running/records.json（{date, distance_km, duration_minutes, pace, source, note?, image_path?}）与 images/running/notes.json；若用户上传跑步截图，请视觉识别公里数、时长与配速并写入 running/records.json；若用户要求同步 COROS 数据，请通过 /home/dutu/.local/bin/coros-mcp（list-tools / call-tool）拉取并将睡眠与跑步写入上述单源 JSON。\n" +
	"   - 自定义量化指标：metrics/<id>.json（{id, name, unit, color, goal, records: [{date, value, note?}]}）\n" +
	"   写入时保持单源整洁，不要重复另写一份 Markdown 清单。\n" +
	"2. 向用户生成并发送文件（Word `.docx`、Excel `.xlsx`、PPT `.pptx`、PDF 或图片）时，必须保存到 `/home/dutu/workspace/personal-assistant/data/exports/<文件名>`（不要保存在 `/tmp` 或 `data/` 之外的目录，否则客户端无法下载预览），并在回复正文附带 `[📄 <文件名>](pace-doc://exports/<文件名>)`（图片用 `[🖼 <文件名>](pace-doc://exports/<文件名>)`），PaceApp 会渲染为可点击的预览/本机打开文件卡片。\n" +
	"3. PaceApp 后端（http://127.0.0.1:8081）也提供个人数据库记录接口 /api/v1/entries（请求头携带 X-Hermes-Key）。当用户要求“记住某句话”、“存进数据库”或“加入待办”时，除了文字回复外，也可以按用户要求写入文档或调用该接口保存到数据库。\n" +
	"4. 当用户要求你在软件中“展示”、“整理成清单/表格/统计/卡片”或查看某类记录时，请尽量在回复末尾附带一个结构化展示块（使用 ```pace-card 代码块），PaceApp 客户端会把它渲染为原生交互卡片并允许用户固定到看板：\n" +
	"```pace-card\n" +
	`{
  "type": "checklist | stats | table | note",
  "title": "卡片标题",
  "subtitle": "副标题（可选）",
  "items": [{"label": "条目内容", "done": false, "detail": "补充说明"}],
  "metrics": [{"label": "指标名", "value": "数值", "unit": "单位"}],
  "columns": ["列1", "列2"],
  "rows": [["值1", "值2"]],
  "content": "note 类型的正文"
}` + "\n```"

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

func enrichChatBody(rawBody []byte) []byte {
	payload := map[string]any{}
	if err := json.Unmarshal(rawBody, &payload); err != nil {
		return rawBody
	}
	if _, has := payload["system_message"]; !has {
		if _, hasInstr := payload["instructions"]; !hasInstr {
			payload["system_message"] = paceSystemPrompt
		}
	}
	encoded, err := json.Marshal(payload)
	if err != nil {
		return rawBody
	}
	return encoded
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
		if v == "paceapp" {
			v = "api_server"
		}
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
	enriched := enrichChatBody(rawBody)
	status, data, err := s.hermes.doJSON(r.Context(), http.MethodPost, "/api/sessions/"+url.PathEscape(id)+"/chat", enriched)
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
	enriched := enrichChatBody(rawBody)
	upstreamURL := s.hermes.baseURL + "/api/sessions/" + url.PathEscape(id) + "/chat/stream"
	req, err := http.NewRequestWithContext(r.Context(), http.MethodPost, upstreamURL, bytes.NewReader(enriched))
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
