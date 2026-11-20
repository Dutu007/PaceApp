package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

var senseVoiceTagRegex = regexp.MustCompile(`<\|[^|\r\n]+\|>`)

type sttClient struct {
	baseURL string
	apiKey  string
	model   string
	http    *http.Client
}

func newSTTClient(baseURL, apiKey, model string) *sttClient {
	key := strings.TrimSpace(apiKey)
	if key == "" {
		return nil
	}
	base := strings.TrimRight(strings.TrimSpace(baseURL), "/")
	if base == "" {
		base = "https://api.siliconflow.cn"
	}
	if !strings.HasSuffix(base, "/v1") {
		base += "/v1"
	}
	m := strings.TrimSpace(model)
	if m == "" {
		m = "FunAudioLLM/SenseVoiceSmall"
	}
	return &sttClient{
		baseURL: base,
		apiKey:  key,
		model:   m,
		http:    &http.Client{Timeout: 30 * time.Second},
	}
}

func cleanTranscriptText(raw string) string {
	stripped := senseVoiceTagRegex.ReplaceAllString(raw, " ")
	return strings.TrimSpace(strings.Join(strings.Fields(stripped), " "))
}

func (c *sttClient) transcribe(ctx context.Context, filename string, audioBytes []byte) (string, error) {
	if c == nil {
		return "", fmt.Errorf("stt_not_configured")
	}
	if strings.TrimSpace(filename) == "" {
		filename = "voice.wav"
	}
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	if err := mw.WriteField("model", c.model); err != nil {
		return "", err
	}
	part, err := mw.CreateFormFile("file", filepath.Base(filename))
	if err != nil {
		return "", err
	}
	if _, err := part.Write(audioBytes); err != nil {
		return "", err
	}
	if err := mw.Close(); err != nil {
		return "", err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/audio/transcriptions", &body)
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", "Bearer "+c.apiKey)
	req.Header.Set("Content-Type", mw.FormDataContentType())

	resp, err := c.http.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	respRaw, err := io.ReadAll(io.LimitReader(resp.Body, 512<<10))
	if err != nil {
		return "", err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", fmt.Errorf("stt_upstream_%d: %s", resp.StatusCode, strings.TrimSpace(string(respRaw)))
	}
	var parsed struct {
		Text string `json:"text"`
	}
	if err := json.Unmarshal(respRaw, &parsed); err != nil {
		return "", err
	}
	return cleanTranscriptText(parsed.Text), nil
}

type refineSttRequest struct {
	Text       string   `json:"text"`
	Candidates []string `json:"candidates"`
	Context    string   `json:"context"`
}

type refineSttResponse struct {
	Text    string `json:"text"`
	Refined bool   `json:"refined"`
}

const sttRefineSystemPrompt = `你是中文语音识别（ASR）后处理纠错助手。用户通过手机语音输入发给个人助手 Hermes，原始识别结果常有同音错别字、缺少标点或中英文专有名词误识别。
请严格遵守以下规则：
1. 只修正明显的同音错别字、常见词汇误识别和标点符号；
2. 常见专有名词参考：Hermes（常被误识别为赫尔墨斯/何美思/河马斯）、PaceApp（常被误识别为配丝艾普/佩斯）、GitHub、Markdown、Go、Android、Docker、PostgreSQL；
3. 如果输入中提供了多个候选结果（candidates），结合语义选择并融合最通顺准确的一句；
4. 绝对不要改变原意、不要回答用户的问题、不要扩写或添加解释；
5. 只输出修正后的纯文本结果。`

type llmClient struct {
	baseURL string
	apiKey  string
	model   string
	http    *http.Client
}

func newLLMClient(baseURL, apiKey, model string) *llmClient {
	key := strings.TrimSpace(apiKey)
	if key == "" {
		return nil
	}
	base := strings.TrimRight(strings.TrimSpace(baseURL), "/")
	if base == "" {
		base = "https://api.deepseek.com"
	}
	if !strings.HasSuffix(base, "/v1") {
		base += "/v1"
	}
	m := strings.TrimSpace(model)
	if m == "" {
		m = "deepseek-chat"
	}
	return &llmClient{
		baseURL: base,
		apiKey:  key,
		model:   m,
		http:    &http.Client{Timeout: 8 * time.Second},
	}
}

func (c *llmClient) refineSpeech(ctx context.Context, primary string, candidates []string, recentContext string) (string, bool) {
	if c == nil {
		return primary, false
	}
	var userPrompt strings.Builder
	userPrompt.WriteString("主识别结果：")
	userPrompt.WriteString(primary)
	if len(candidates) > 1 {
		userPrompt.WriteString("\n其他候选：")
		for idx, cand := range candidates {
			if idx > 3 {
				break
			}
			t := strings.TrimSpace(cand)
			if t != "" && t != primary {
				userPrompt.WriteString("\n- ")
				userPrompt.WriteString(t)
			}
		}
	}
	if ctxTrim := strings.TrimSpace(recentContext); ctxTrim != "" {
		userPrompt.WriteString("\n当前输入上下文：")
		userPrompt.WriteString(ctxTrim)
	}

	reqPayload := map[string]any{
		"model": c.model,
		"messages": []map[string]string{
			{"role": "system", "content": sttRefineSystemPrompt},
			{"role": "user", "content": userPrompt.String()},
		},
		"temperature": 0.1,
		"max_tokens":  400,
	}
	encoded, err := json.Marshal(reqPayload)
	if err != nil {
		return primary, false
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/chat/completions", bytes.NewReader(encoded))
	if err != nil {
		return primary, false
	}
	req.Header.Set("Authorization", "Bearer "+c.apiKey)
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.http.Do(req)
	if err != nil {
		return primary, false
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return primary, false
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 256<<10))
	if err != nil {
		return primary, false
	}
	var parsed struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil || len(parsed.Choices) == 0 {
		return primary, false
	}
	out := strings.TrimSpace(parsed.Choices[0].Message.Content)
	if out == "" {
		return primary, false
	}
	return out, true
}

func (s *server) refineStt(w http.ResponseWriter, r *http.Request) {
	var req refineSttRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	primary := strings.TrimSpace(req.Text)
	if primary == "" && len(req.Candidates) > 0 {
		primary = strings.TrimSpace(req.Candidates[0])
	}
	if primary == "" {
		writeJSON(w, http.StatusOK, refineSttResponse{Text: "", Refined: false})
		return
	}
	refined, ok := s.llm.refineSpeech(r.Context(), primary, req.Candidates, req.Context)
	writeJSON(w, http.StatusOK, refineSttResponse{Text: refined, Refined: ok})
}

func (s *server) transcribeStt(w http.ResponseWriter, r *http.Request) {
	if s.stt == nil {
		errorJSON(w, http.StatusServiceUnavailable, "stt_not_configured")
		return
	}
	_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(45 * time.Second))
	_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(45 * time.Second))

	r.Body = http.MaxBytesReader(w, r.Body, 16<<20)
	if err := r.ParseMultipartForm(16 << 20); err != nil {
		errorJSON(w, http.StatusBadRequest, "invalid_audio_upload")
		return
	}
	file, header, err := r.FormFile("audio")
	if err != nil {
		file, header, err = r.FormFile("file")
		if err != nil {
			errorJSON(w, http.StatusBadRequest, "missing_audio")
			return
		}
	}
	defer file.Close()

	audioBytes, err := io.ReadAll(io.LimitReader(file, 15<<20))
	if err != nil {
		errorJSON(w, http.StatusBadRequest, "invalid_audio_upload")
		return
	}
	if len(audioBytes) < 1000 {
		errorJSON(w, http.StatusBadRequest, "audio_too_short")
		return
	}

	rawText, err := s.stt.transcribe(r.Context(), header.Filename, audioBytes)
	if err != nil {
		errorJSON(w, http.StatusBadGateway, "stt_upstream_failed")
		return
	}
	if rawText == "" {
		writeJSON(w, http.StatusOK, map[string]any{
			"text":     "",
			"raw_text": "",
			"refined":  false,
			"provider": "siliconflow",
		})
		return
	}

	contextDraft := strings.TrimSpace(r.FormValue("context"))
	finalText, refined := s.llm.refineSpeech(r.Context(), rawText, nil, contextDraft)
	writeJSON(w, http.StatusOK, map[string]any{
		"text":     finalText,
		"raw_text": rawText,
		"refined":  refined,
		"provider": "siliconflow",
	})
}
