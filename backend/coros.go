package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	defaultCorosMcpDir     = "/data/coros-mcp"
	defaultRunningFileNote = "跑步记录（单一数据源）。date 为运动日期 YYYY-MM-DD；note 汇总手写备注与手表数据；image_path 指向跑步截图。全部记录均来自 COROS 同步或截图识别，不再另设 notes.json。"
)

var (
	errCorosUnavailable = errors.New("coros_unavailable")
	errCorosAuthExpired = errors.New("coros_auth_expired")

	corosSleepDateLineRe  = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)
	corosSportHeaderRe    = regexp.MustCompile(`^\d+\.\s+(.+?)\s+[—–-]\s+(\d{4}-\d{2}-\d{2})\s*$`)
	corosDurationTextRe   = regexp.MustCompile(`(?:(\d+)\s*h)?\s*(?:(\d+)\s*min(?:ute)?s?)?`)
	corosPercentRe        = regexp.MustCompile(`(\d+)\s*%`)
	corosDistanceKmRe     = regexp.MustCompile(`Distance:\s*([0-9]+(?:\.[0-9]+)?)\s*km`)
	corosClockDurationRe  = regexp.MustCompile(`Duration:\s*([0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)`)
	corosPaceRe           = regexp.MustCompile(`Average Pace:\s*([0-9]{1,2}):([0-9]{2})\s*/km`)
	corosSpeedRe          = regexp.MustCompile(`Average Speed:\s*([0-9]+(?:\.[0-9]+)?)\s*km/h`)
	corosAvgHrRe          = regexp.MustCompile(`Avg HR:\s*(\d+)\s*bpm`)
	corosCaloriesRe       = regexp.MustCompile(`Calories:\s*(\d+)\s*kcal`)
	corosSportTypeRe      = regexp.MustCompile(`SportType:\s*(\d+)`)
)

type corosTokenSet struct {
	AccessToken    string `json:"access_token"`
	RefreshToken   string `json:"refresh_token"`
	ExpiresAtEpoch int64  `json:"expires_at_epoch"`
	TokenType      string `json:"token_type"`
	Scope          string `json:"scope,omitempty"`
	ClientID       string `json:"client_id,omitempty"`
}

type corosRegionEndpoint struct {
	region    string
	issuer    string
	mcpURL    string
	tokenPath string
}

type corosClient struct {
	mcpDir         string
	overrideIssuer string
	overrideMcpURL string
	http           *http.Client
	nowFn          func() time.Time
	tokenMu        sync.Mutex
}

type corosSyncResult struct {
	OK                bool   `json:"ok"`
	SleepSynced       int    `json:"sleep_synced"`
	SleepAdded        int    `json:"sleep_added"`
	SleepUpdated      int    `json:"sleep_updated"`
	RunningSynced     int    `json:"running_synced"`
	RunningAdded      int    `json:"running_added"`
	RunningUpdated    int    `json:"running_updated"`
	CyclingSynced     int    `json:"cycling_synced"`
	CyclingAdded      int    `json:"cycling_added"`
	CyclingUpdated    int    `json:"cycling_updated"`
	LatestSleepDate   string `json:"latest_sleep_date,omitempty"`
	LatestRunningDate string `json:"latest_running_date,omitempty"`
	SyncedAt          string `json:"synced_at"`
}

type corosSportEntry struct {
	Date            string
	SportType       int
	Title           string
	DistanceKm      float64
	DurationMinutes int
	DurationText    string
	Pace            string
	SpeedKmh        float64
	AvgHR           int
	Calories        int
}

func newCorosClientFromEnv() *corosClient {
	dir := strings.TrimSpace(os.Getenv("COROS_MCP_DIR"))
	if dir == "" {
		dir = defaultCorosMcpDir
	}
	return &corosClient{
		mcpDir: dir,
		http:   &http.Client{Timeout: 25 * time.Second},
		nowFn:  time.Now,
	}
}

func (c *corosClient) resolveEndpoint() (corosRegionEndpoint, error) {
	if c == nil || strings.TrimSpace(c.mcpDir) == "" {
		return corosRegionEndpoint{}, errCorosUnavailable
	}
	regions := []struct {
		name   string
		issuer string
	}{
		{"cn", "https://mcpcn.coros.com"},
		{"eu", "https://mcpeu.coros.com"},
		{"us", "https://mcpus.coros.com"},
	}
	for _, r := range regions {
		tokPath := filepath.Join(c.mcpDir, r.name, "token.json")
		if info, err := os.Stat(tokPath); err == nil && !info.IsDir() {
			issuer := r.issuer
			if strings.TrimSpace(c.overrideIssuer) != "" {
				issuer = strings.TrimRight(strings.TrimSpace(c.overrideIssuer), "/")
			}
			mcpURL := issuer + "/mcp"
			if strings.TrimSpace(c.overrideMcpURL) != "" {
				mcpURL = strings.TrimSpace(c.overrideMcpURL)
			}
			return corosRegionEndpoint{
				region:    r.name,
				issuer:    issuer,
				mcpURL:    mcpURL,
				tokenPath: tokPath,
			}, nil
		}
	}
	directTok := filepath.Join(c.mcpDir, "token.json")
	if info, err := os.Stat(directTok); err == nil && !info.IsDir() {
		issuer := "https://mcpcn.coros.com"
		if strings.TrimSpace(c.overrideIssuer) != "" {
			issuer = strings.TrimRight(strings.TrimSpace(c.overrideIssuer), "/")
		}
		mcpURL := issuer + "/mcp"
		if strings.TrimSpace(c.overrideMcpURL) != "" {
			mcpURL = strings.TrimSpace(c.overrideMcpURL)
		}
		return corosRegionEndpoint{
			region:    "default",
			issuer:    issuer,
			mcpURL:    mcpURL,
			tokenPath: directTok,
		}, nil
	}
	return corosRegionEndpoint{}, errCorosAuthExpired
}

func writeJSONAtomicPrivate(path string, v any) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	raw, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	raw = append(raw, '\n')
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func (c *corosClient) ensureToken(ctx context.Context, ep corosRegionEndpoint, forceRefresh bool) (corosTokenSet, error) {
	c.tokenMu.Lock()
	defer c.tokenMu.Unlock()

	raw, err := os.ReadFile(ep.tokenPath)
	if err != nil {
		return corosTokenSet{}, errCorosAuthExpired
	}
	var tok corosTokenSet
	if err := json.Unmarshal(raw, &tok); err != nil || strings.TrimSpace(tok.AccessToken) == "" {
		return corosTokenSet{}, errCorosAuthExpired
	}
	nowUnix := c.nowFn().Unix()
	if !forceRefresh && (tok.ExpiresAtEpoch <= 0 || tok.ExpiresAtEpoch > nowUnix+60) {
		return tok, nil
	}
	if strings.TrimSpace(tok.RefreshToken) == "" {
		return corosTokenSet{}, errCorosAuthExpired
	}
	refreshed, err := c.refreshOAuthToken(ctx, ep, tok)
	if err != nil {
		return corosTokenSet{}, err
	}
	_ = writeJSONAtomicPrivate(ep.tokenPath, refreshed)
	return refreshed, nil
}

func (c *corosClient) refreshOAuthToken(ctx context.Context, ep corosRegionEndpoint, prev corosTokenSet) (corosTokenSet, error) {
	form := url.Values{}
	form.Set("grant_type", "refresh_token")
	form.Set("refresh_token", prev.RefreshToken)
	if strings.TrimSpace(prev.ClientID) != "" {
		form.Set("client_id", prev.ClientID)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, ep.issuer+"/oauth2/token", strings.NewReader(form.Encode()))
	if err != nil {
		return corosTokenSet{}, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")

	resp, err := c.http.Do(req)
	if err != nil {
		return corosTokenSet{}, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, 512<<10))
	if err != nil {
		return corosTokenSet{}, err
	}
	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusBadRequest || resp.StatusCode == http.StatusForbidden {
		return corosTokenSet{}, errCorosAuthExpired
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return corosTokenSet{}, fmt.Errorf("coros token refresh status %d", resp.StatusCode)
	}
	var payload struct {
		AccessToken  string `json:"access_token"`
		RefreshToken string `json:"refresh_token"`
		ExpiresIn    int64  `json:"expires_in"`
		TokenType    string `json:"token_type"`
		Scope        string `json:"scope"`
	}
	if err := json.Unmarshal(body, &payload); err != nil || strings.TrimSpace(payload.AccessToken) == "" {
		return corosTokenSet{}, fmt.Errorf("invalid coros refresh response")
	}
	next := corosTokenSet{
		AccessToken:  strings.TrimSpace(payload.AccessToken),
		RefreshToken: strings.TrimSpace(payload.RefreshToken),
		TokenType:    strings.TrimSpace(payload.TokenType),
		Scope:        strings.TrimSpace(payload.Scope),
		ClientID:     prev.ClientID,
	}
	if next.RefreshToken == "" {
		next.RefreshToken = prev.RefreshToken
	}
	if next.TokenType == "" {
		next.TokenType = "Bearer"
	}
	if next.Scope == "" {
		next.Scope = prev.Scope
	}
	if payload.ExpiresIn > 0 {
		next.ExpiresAtEpoch = c.nowFn().Unix() + payload.ExpiresIn
	}
	return next, nil
}

func (c *corosClient) callTool(ctx context.Context, toolName string, args map[string]any) (string, error) {
	ep, err := c.resolveEndpoint()
	if err != nil {
		return "", err
	}
	tok, err := c.ensureToken(ctx, ep, false)
	if err != nil {
		return "", err
	}
	text, statusCode, err := c.runMcpSequence(ctx, ep.mcpURL, tok, toolName, args)
	if err == nil {
		return text, nil
	}
	if statusCode == http.StatusUnauthorized {
		refreshed, rErr := c.ensureToken(ctx, ep, true)
		if rErr != nil {
			return "", rErr
		}
		text, statusCode, err = c.runMcpSequence(ctx, ep.mcpURL, refreshed, toolName, args)
		if statusCode == http.StatusUnauthorized {
			return "", errCorosAuthExpired
		}
	}
	return text, err
}

func (c *corosClient) runMcpSequence(
	ctx context.Context,
	mcpURL string,
	tok corosTokenSet,
	toolName string,
	args map[string]any,
) (string, int, error) {
	initReq := map[string]any{
		"jsonrpc": "2.0",
		"id":      1,
		"method":  "initialize",
		"params": map[string]any{
			"protocolVersion": "2025-06-18",
			"capabilities":    map[string]any{},
			"clientInfo": map[string]any{
				"name":    "PaceApp Direct Sync",
				"version": "1.0.0",
			},
		},
	}
	_, sessionID, status, err := c.postMcpJSON(ctx, mcpURL, tok, "", initReq)
	if err != nil {
		return "", status, err
	}

	if args == nil {
		args = map[string]any{}
	}
	callReq := map[string]any{
		"jsonrpc": "2.0",
		"id":      2,
		"method":  "tools/call",
		"params": map[string]any{
			"name":      toolName,
			"arguments": args,
		},
	}
	payload, _, status, err := c.postMcpJSON(ctx, mcpURL, tok, sessionID, callReq)
	if err != nil {
		return "", status, err
	}
	var rpcResp struct {
		Result *struct {
			IsError bool `json:"isError"`
			Content []struct {
				Type string `json:"type"`
				Text string `json:"text"`
			} `json:"content"`
		} `json:"result"`
		Error *struct {
			Code    int    `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(payload, &rpcResp); err != nil {
		return "", status, err
	}
	if rpcResp.Error != nil {
		return "", status, fmt.Errorf("coros mcp error %d: %s", rpcResp.Error.Code, rpcResp.Error.Message)
	}
	if rpcResp.Result == nil || len(rpcResp.Result.Content) == 0 {
		return "", status, fmt.Errorf("coros mcp empty result")
	}
	text := unwrapCorosContentText(rpcResp.Result.Content[0].Text)
	if rpcResp.Result.IsError {
		lower := strings.ToLower(text)
		if strings.Contains(lower, "unauthorized") || strings.Contains(lower, "token") || strings.Contains(lower, "login") {
			return "", http.StatusUnauthorized, errCorosAuthExpired
		}
		return "", status, fmt.Errorf("coros tool error: %s", text)
	}
	return text, status, nil
}

func (c *corosClient) postMcpJSON(
	ctx context.Context,
	mcpURL string,
	tok corosTokenSet,
	sessionID string,
	reqBody any,
) ([]byte, string, int, error) {
	rawReq, err := json.Marshal(reqBody)
	if err != nil {
		return nil, "", 0, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, mcpURL, bytes.NewReader(rawReq))
	if err != nil {
		return nil, "", 0, err
	}
	tokenType := strings.TrimSpace(tok.TokenType)
	if tokenType == "" {
		tokenType = "Bearer"
	}
	req.Header.Set("Authorization", tokenType+" "+tok.AccessToken)
	req.Header.Set("Accept", "application/json, text/event-stream")
	req.Header.Set("Content-Type", "application/json")
	if sessionID != "" {
		req.Header.Set("Mcp-Session-Id", sessionID)
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, "", 0, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, "", resp.StatusCode, err
	}
	if resp.StatusCode == http.StatusUnauthorized {
		return nil, "", resp.StatusCode, errCorosAuthExpired
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, "", resp.StatusCode, fmt.Errorf("coros mcp status %d", resp.StatusCode)
	}
	nextSessionID := strings.TrimSpace(resp.Header.Get("Mcp-Session-Id"))
	if nextSessionID == "" {
		nextSessionID = sessionID
	}

	contentType := strings.ToLower(resp.Header.Get("Content-Type"))
	if strings.Contains(contentType, "text/event-stream") || bytes.HasPrefix(bytes.TrimSpace(body), []byte("event:")) || bytes.HasPrefix(bytes.TrimSpace(body), []byte("data:")) {
		extracted := extractLastSSEData(string(body))
		if len(extracted) == 0 {
			return nil, nextSessionID, resp.StatusCode, fmt.Errorf("empty coros sse payload")
		}
		return extracted, nextSessionID, resp.StatusCode, nil
	}
	return body, nextSessionID, resp.StatusCode, nil
}

func extractLastSSEData(raw string) []byte {
	var payloads []string
	var cur []string
	for _, line := range strings.Split(strings.ReplaceAll(raw, "\r\n", "\n"), "\n") {
		if line == "" {
			if len(cur) > 0 {
				payloads = append(payloads, strings.Join(cur, "\n"))
				cur = cur[:0]
			}
			continue
		}
		if strings.HasPrefix(line, "data:") {
			cur = append(cur, strings.TrimSpace(strings.TrimPrefix(line, "data:")))
		}
	}
	if len(cur) > 0 {
		payloads = append(payloads, strings.Join(cur, "\n"))
	}
	if len(payloads) == 0 {
		return nil
	}
	return []byte(payloads[len(payloads)-1])
}

func unwrapCorosContentText(raw string) string {
	trimmed := strings.TrimSpace(raw)
	if strings.HasPrefix(trimmed, "\"") && strings.HasSuffix(trimmed, "\"") {
		if unq, err := strconv.Unquote(trimmed); err == nil {
			return strings.ReplaceAll(unq, "\r\n", "\n")
		}
	}
	return strings.ReplaceAll(raw, "\r\n", "\n")
}

func parseCorosTextDurationMinutes(raw string) int {
	clean := strings.TrimSpace(raw)
	if clean == "" {
		return 0
	}
	m := corosDurationTextRe.FindStringSubmatch(clean)
	if m == nil || (m[1] == "" && m[2] == "") {
		return 0
	}
	hours, _ := strconv.Atoi(m[1])
	mins, _ := strconv.Atoi(m[2])
	return hours*60 + mins
}

func parseCorosPercent(raw string) (int, bool) {
	m := corosPercentRe.FindStringSubmatch(raw)
	if m == nil {
		return 0, false
	}
	v, err := strconv.Atoi(m[1])
	if err != nil {
		return 0, false
	}
	return v, true
}

func parseCorosSleepOverview(text string) []sleepRecordItem {
	lines := strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n")
	type sleepSection struct {
		date  string
		lines []string
	}
	var sections []sleepSection
	var current *sleepSection
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if corosSleepDateLineRe.MatchString(trimmed) {
			sections = append(sections, sleepSection{date: trimmed})
			current = &sections[len(sections)-1]
			continue
		}
		if current != nil {
			current.lines = append(current.lines, trimmed)
		}
	}

	out := make([]sleepRecordItem, 0, len(sections))
	for _, sec := range sections {
		joined := strings.Join(sec.lines, "\n")
		if strings.Contains(strings.ToLower(joined), "not available yet") {
			continue
		}
		var (
			score        int
			asleepMin    int
			deepPct      = -1
			lightPct     = -1
			remPct       = -1
			awakeMin     = -1
			bedtime      string
			wakeTime     string
			napsMin      int
		)
		for _, ln := range sec.lines {
			switch {
			case strings.HasPrefix(ln, "Sleep Score:"):
				score, _ = strconv.Atoi(strings.TrimSpace(strings.TrimPrefix(ln, "Sleep Score:")))
			case strings.HasPrefix(ln, "Main Sleep (asleep):"):
				asleepMin = parseCorosTextDurationMinutes(strings.TrimPrefix(ln, "Main Sleep (asleep):"))
			case strings.HasPrefix(ln, "Deep Sleep Ratio:"):
				if v, ok := parseCorosPercent(strings.TrimPrefix(ln, "Deep Sleep Ratio:")); ok {
					deepPct = v
				}
			case strings.HasPrefix(ln, "Light Sleep Ratio:"):
				if v, ok := parseCorosPercent(strings.TrimPrefix(ln, "Light Sleep Ratio:")); ok {
					lightPct = v
				}
			case strings.HasPrefix(ln, "REM Ratio:"):
				if v, ok := parseCorosPercent(strings.TrimPrefix(ln, "REM Ratio:")); ok {
					remPct = v
				}
			case strings.HasPrefix(ln, "Awake Time:"):
				awakeMin = parseCorosTextDurationMinutes(strings.TrimPrefix(ln, "Awake Time:"))
			case strings.HasPrefix(ln, "Main Sleep Window:"):
				win := strings.TrimSpace(strings.TrimPrefix(ln, "Main Sleep Window:"))
				parts := strings.Split(win, " - ")
				if len(parts) == 2 {
					bedtime = strings.TrimSpace(parts[0])
					wakeTime = strings.TrimSpace(parts[1])
				}
			case strings.HasPrefix(ln, "Naps Total (asleep):"):
				napsMin = parseCorosTextDurationMinutes(strings.TrimPrefix(ln, "Naps Total (asleep):"))
			}
		}
		if asleepMin <= 0 || score <= 0 {
			continue
		}
		item := sleepRecordItem{
			Date:            sec.date,
			Bedtime:         bedtime,
			WakeTime:        wakeTime,
			DurationMinutes: asleepMin,
			Source:          "coros",
			Note:            "无午睡",
		}
		if score > 0 {
			sc := score
			item.Score = &sc
		}
		if deepPct >= 0 {
			v := int(math.Round(float64(asleepMin) * float64(deepPct) / 100.0))
			item.DeepMinutes = &v
		}
		if lightPct >= 0 {
			v := int(math.Round(float64(asleepMin) * float64(lightPct) / 100.0))
			item.LightMinutes = &v
		}
		if remPct >= 0 {
			v := int(math.Round(float64(asleepMin) * float64(remPct) / 100.0))
			item.RemMinutes = &v
		}
		if awakeMin >= 0 {
			v := awakeMin
			item.AwakeMinutes = &v
		}
		if napsMin > 0 {
			item.Note = fmt.Sprintf("午睡 %d 分钟", napsMin)
		}
		out = append(out, item)
	}
	return out
}

func parseClockDurationRoundedMinutes(raw string) int {
	parts := strings.Split(strings.TrimSpace(raw), ":")
	switch len(parts) {
	case 3:
		h, _ := strconv.Atoi(parts[0])
		m, _ := strconv.Atoi(parts[1])
		s, _ := strconv.Atoi(parts[2])
		return int(math.Round(float64(h*3600+m*60+s) / 60.0))
	case 2:
		m, _ := strconv.Atoi(parts[0])
		s, _ := strconv.Atoi(parts[1])
		return int(math.Round(float64(m*60+s) / 60.0))
	default:
		return 0
	}
}

func parseCorosSportRecords(text string) ([]corosSportEntry, []corosSportEntry) {
	lines := strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n")
	type sportBlock struct {
		title string
		date  string
		body  string
	}
	var blocks []sportBlock
	var curLines []string
	var curTitle, curDate string
	flush := func() {
		if curDate != "" && len(curLines) > 0 {
			blocks = append(blocks, sportBlock{
				title: curTitle,
				date:  curDate,
				body:  strings.Join(curLines, "\n"),
			})
		}
		curLines = nil
		curTitle = ""
		curDate = ""
	}
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if m := corosSportHeaderRe.FindStringSubmatch(trimmed); m != nil {
			flush()
			curTitle = strings.TrimSpace(m[1])
			curDate = strings.TrimSpace(m[2])
			curLines = append(curLines, trimmed)
			continue
		}
		if curDate != "" {
			curLines = append(curLines, trimmed)
		}
	}
	flush()

	var runs []corosSportEntry
	var rides []corosSportEntry
	for _, b := range blocks {
		mType := corosSportTypeRe.FindStringSubmatch(b.body)
		if mType == nil {
			continue
		}
		sportType, err := strconv.Atoi(mType[1])
		if err != nil {
			continue
		}
		entry := corosSportEntry{
			Date:      b.date,
			SportType: sportType,
			Title:     b.title,
		}
		if m := corosDistanceKmRe.FindStringSubmatch(b.body); m != nil {
			entry.DistanceKm, _ = strconv.ParseFloat(m[1], 64)
			entry.DistanceKm = math.Round(entry.DistanceKm*100) / 100
		}
		if m := corosClockDurationRe.FindStringSubmatch(b.body); m != nil {
			entry.DurationText = strings.TrimSpace(m[1])
			entry.DurationMinutes = parseClockDurationRoundedMinutes(entry.DurationText)
		}
		if m := corosPaceRe.FindStringSubmatch(b.body); m != nil {
			entry.Pace = fmt.Sprintf("%s'%s\"/km", m[1], m[2])
		}
		if m := corosSpeedRe.FindStringSubmatch(b.body); m != nil {
			entry.SpeedKmh, _ = strconv.ParseFloat(m[1], 64)
		}
		if m := corosAvgHrRe.FindStringSubmatch(b.body); m != nil {
			entry.AvgHR, _ = strconv.Atoi(m[1])
		}
		if m := corosCaloriesRe.FindStringSubmatch(b.body); m != nil {
			entry.Calories, _ = strconv.Atoi(m[1])
		}

		switch {
		case sportType >= 100 && sportType <= 103:
			runs = append(runs, entry)
		case (sportType >= 200 && sportType <= 205) || sportType == 299:
			rides = append(rides, entry)
		}
	}
	return runs, rides
}

func isAutoCorosSleepNote(note string) bool {
	trimmed := strings.TrimSpace(note)
	return trimmed == "" || trimmed == "无午睡" || strings.HasPrefix(trimmed, "午睡 ")
}

func isAutoCorosSportNote(note string) bool {
	trimmed := strings.TrimSpace(note)
	return trimmed == "" || strings.HasPrefix(trimmed, "平均心率 ") || strings.HasPrefix(trimmed, "COROS")
}

func formatCorosMetricNote(avgHR, calories int) string {
	var parts []string
	if avgHR > 0 {
		parts = append(parts, fmt.Sprintf("平均心率 %d bpm", avgHR))
	}
	if calories > 0 {
		parts = append(parts, fmt.Sprintf("%d kcal", calories))
	}
	return strings.Join(parts, " · ")
}

func equalIntPtr(a, b *int) bool {
	if a == nil && b == nil {
		return true
	}
	if a == nil || b == nil {
		return false
	}
	return *a == *b
}

func (d *docStore) mergeCorosSleepLocked(incoming []sleepRecordItem) (added int, updated int, latestDate string, err error) {
	if d.assistantDir == "" {
		return 0, 0, "", fmt.Errorf("assistant_dir_unset")
	}
	if len(incoming) == 0 {
		return 0, 0, "", nil
	}
	sleepFile, err := d.loadSleepLocked()
	if err != nil {
		return 0, 0, "", err
	}
	byDate := make(map[string]sleepRecordItem, len(sleepFile.Records)+len(incoming))
	for _, rec := range sleepFile.Records {
		dateKey := strings.TrimSpace(rec.Date)
		if dateKey != "" {
			byDate[dateKey] = rec
		}
	}
	for _, item := range incoming {
		if !isAutoCorosSleepNote(item.Note) {
			item.Note = strings.TrimSpace(item.Note)
		}
		if existing, ok := byDate[item.Date]; ok {
			if !isAutoCorosSleepNote(existing.Note) {
				item.Note = existing.Note
			}
			same := existing.Bedtime == item.Bedtime &&
				existing.WakeTime == item.WakeTime &&
				existing.DurationMinutes == item.DurationMinutes &&
				equalIntPtr(existing.DeepMinutes, item.DeepMinutes) &&
				equalIntPtr(existing.LightMinutes, item.LightMinutes) &&
				equalIntPtr(existing.RemMinutes, item.RemMinutes) &&
				equalIntPtr(existing.AwakeMinutes, item.AwakeMinutes) &&
				equalIntPtr(existing.Score, item.Score) &&
				existing.Source == item.Source &&
				existing.Note == item.Note
			if !same {
				updated++
			}
		} else {
			added++
		}
		byDate[item.Date] = item
	}

	merged := make([]sleepRecordItem, 0, len(byDate))
	for _, rec := range byDate {
		merged = append(merged, rec)
		if rec.Date > latestDate {
			latestDate = rec.Date
		}
	}
	sort.Slice(merged, func(i, j int) bool {
		return merged[i].Date < merged[j].Date
	})
	if added > 0 || updated > 0 {
		sleepFile.Records = merged
		full := filepath.Join(d.assistantDir, "sleep", "records.json")
		if err := writeJSONAtomic(full, sleepFile); err != nil {
			return added, updated, latestDate, err
		}
	}
	return added, updated, latestDate, nil
}

// mergeCorosRunningLocked preserves unknown keys (such as memo and handwritten notes) in running/records.json.
func (d *docStore) mergeCorosRunningLocked(incoming []corosSportEntry) (added int, updated int, latestDate string, err error) {
	if d.assistantDir == "" {
		return 0, 0, "", fmt.Errorf("assistant_dir_unset")
	}
	if len(incoming) == 0 {
		return 0, 0, "", nil
	}
	full := filepath.Join(d.assistantDir, "running", "records.json")
	doc := struct {
		Note    string           `json:"note"`
		Records []map[string]any `json:"records"`
	}{
		Note:    defaultRunningFileNote,
		Records: []map[string]any{},
	}
	if raw, rErr := os.ReadFile(full); rErr == nil {
		if uErr := json.Unmarshal(raw, &doc); uErr != nil {
			return 0, 0, "", uErr
		}
		if strings.TrimSpace(doc.Note) == "" {
			doc.Note = defaultRunningFileNote
		}
	} else if !os.IsNotExist(rErr) {
		return 0, 0, "", rErr
	}

	byDate := make(map[string]map[string]any, len(doc.Records)+len(incoming))
	for _, rec := range doc.Records {
		dateKey := strings.TrimSpace(fmt.Sprint(rec["date"]))
		if dateYMDPattern.MatchString(dateKey) {
			byDate[dateKey] = rec
		}
	}

	for _, run := range incoming {
		noteText := formatCorosMetricNote(run.AvgHR, run.Calories)
		if existing, ok := byDate[run.Date]; ok {
			existingSrc := strings.ToLower(strings.TrimSpace(fmt.Sprint(existing["source"])))
			if existingSrc == "<nil>" {
				existingSrc = ""
			}
			// Preserve Keep/handwritten entries while backfilling missing numeric telemetry cleanly.
			if existingSrc != "" && existingSrc != "coros" {
				continue
			}
			before, _ := json.Marshal(existing)
			if run.DistanceKm > 0 {
				existing["distance_km"] = run.DistanceKm
			}
			if run.DurationMinutes > 0 {
				existing["duration_minutes"] = run.DurationMinutes
			}
			if run.Pace != "" {
				existing["pace"] = run.Pace
			}
			if existingSrc == "" {
				existing["source"] = "coros"
			}
			existingNote, _ := existing["note"].(string)
			if noteText != "" && isAutoCorosSportNote(existingNote) {
				existing["note"] = noteText
			}
			after, _ := json.Marshal(existing)
			if !bytes.Equal(before, after) {
				updated++
			}
			continue
		}

		rec := map[string]any{
			"date":   run.Date,
			"source": "coros",
		}
		if run.DistanceKm > 0 {
			rec["distance_km"] = run.DistanceKm
		}
		if run.DurationMinutes > 0 {
			rec["duration_minutes"] = run.DurationMinutes
		}
		if run.Pace != "" {
			rec["pace"] = run.Pace
		}
		if noteText != "" {
			rec["note"] = noteText
		}
		byDate[run.Date] = rec
		added++
	}

	merged := make([]map[string]any, 0, len(byDate))
	for dKey, rec := range byDate {
		merged = append(merged, rec)
		if dKey > latestDate {
			latestDate = dKey
		}
	}
	sort.Slice(merged, func(i, j int) bool {
		return fmt.Sprint(merged[i]["date"]) < fmt.Sprint(merged[j]["date"])
	})
	if added > 0 || updated > 0 {
		doc.Records = merged
		if err := writeJSONAtomic(full, doc); err != nil {
			return added, updated, latestDate, err
		}
	}
	return added, updated, latestDate, nil
}

func (d *docStore) mergeCorosCyclingLocked(incoming []corosSportEntry) (added int, updated int, err error) {
	if d.assistantDir == "" {
		return 0, 0, fmt.Errorf("assistant_dir_unset")
	}
	if len(incoming) == 0 {
		return 0, 0, nil
	}
	full := filepath.Join(d.assistantDir, "metrics", "cycling.json")
	doc := struct {
		ID      string           `json:"id"`
		Name    string           `json:"name"`
		Unit    string           `json:"unit"`
		Color   string           `json:"color"`
		Goal    any              `json:"goal"`
		Records []map[string]any `json:"records"`
	}{
		ID:      "cycling",
		Name:    "骑行",
		Unit:    "km",
		Color:   "#2E7D32",
		Goal:    nil,
		Records: []map[string]any{},
	}
	if raw, rErr := os.ReadFile(full); rErr == nil {
		if uErr := json.Unmarshal(raw, &doc); uErr != nil {
			return 0, 0, uErr
		}
		if strings.TrimSpace(doc.ID) == "" {
			doc.ID = "cycling"
		}
		if strings.TrimSpace(doc.Name) == "" {
			doc.Name = "骑行"
		}
		if strings.TrimSpace(doc.Unit) == "" {
			doc.Unit = "km"
		}
		if strings.TrimSpace(doc.Color) == "" {
			doc.Color = "#2E7D32"
		}
	} else if !os.IsNotExist(rErr) {
		return 0, 0, rErr
	}

	byDate := make(map[string]map[string]any, len(doc.Records)+len(incoming))
	for _, rec := range doc.Records {
		dateKey := strings.TrimSpace(fmt.Sprint(rec["date"]))
		if dateYMDPattern.MatchString(dateKey) {
			byDate[dateKey] = rec
		}
	}

	for _, ride := range incoming {
		noteText := formatCorosMetricNote(ride.AvgHR, ride.Calories)
		if existing, ok := byDate[ride.Date]; ok {
			existingSrc := strings.ToLower(strings.TrimSpace(fmt.Sprint(existing["source"])))
			if existingSrc == "<nil>" {
				existingSrc = ""
			}
			if existingSrc != "" && existingSrc != "coros" {
				continue
			}
			before, _ := json.Marshal(existing)
			if ride.DistanceKm > 0 {
				existing["value"] = ride.DistanceKm
				existing["distance_km"] = ride.DistanceKm
			}
			if ride.DurationMinutes > 0 {
				existing["duration_minutes"] = ride.DurationMinutes
			}
			if ride.DurationText != "" {
				existing["duration_text"] = ride.DurationText
			}
			if ride.SpeedKmh > 0 {
				existing["speed_kmh"] = ride.SpeedKmh
			}
			if ride.AvgHR > 0 {
				existing["avg_hr"] = ride.AvgHR
			}
			if ride.Calories > 0 {
				existing["calories"] = ride.Calories
			}
			if existingSrc == "" {
				existing["source"] = "coros"
			}
			existingNote, _ := existing["note"].(string)
			if noteText != "" && isAutoCorosSportNote(existingNote) {
				existing["note"] = noteText
			}
			after, _ := json.Marshal(existing)
			if !bytes.Equal(before, after) {
				updated++
			}
			continue
		}

		rec := map[string]any{
			"date":        ride.Date,
			"value":       ride.DistanceKm,
			"distance_km": ride.DistanceKm,
			"source":      "coros",
		}
		if ride.DurationMinutes > 0 {
			rec["duration_minutes"] = ride.DurationMinutes
		}
		if ride.DurationText != "" {
			rec["duration_text"] = ride.DurationText
		}
		if ride.SpeedKmh > 0 {
			rec["speed_kmh"] = ride.SpeedKmh
		}
		if ride.AvgHR > 0 {
			rec["avg_hr"] = ride.AvgHR
		}
		if ride.Calories > 0 {
			rec["calories"] = ride.Calories
		}
		if noteText != "" {
			rec["note"] = noteText
		}
		byDate[ride.Date] = rec
		added++
	}

	merged := make([]map[string]any, 0, len(byDate))
	for _, rec := range byDate {
		merged = append(merged, rec)
	}
	sort.Slice(merged, func(i, j int) bool {
		return fmt.Sprint(merged[i]["date"]) < fmt.Sprint(merged[j]["date"])
	})
	if added > 0 || updated > 0 {
		doc.Records = merged
		if err := writeJSONAtomic(full, doc); err != nil {
			return added, updated, err
		}
	}
	return added, updated, nil
}

func (s *server) syncCorosAnalytics(w http.ResponseWriter, r *http.Request) {
	if s.coros == nil || s.docs == nil || s.docs.assistantDir == "" {
		errorJSON(w, http.StatusServiceUnavailable, "coros_sync_unavailable")
		return
	}
	var req struct {
		SleepDays int `json:"sleep_days"`
		SportDays int `json:"sport_days"`
	}
	if r.Body != nil {
		_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<10)).Decode(&req)
	}
	if req.SleepDays <= 0 {
		req.SleepDays = 7
	} else if req.SleepDays > 30 {
		req.SleepDays = 30
	}
	if req.SportDays <= 0 {
		req.SportDays = 14
	} else if req.SportDays > 30 {
		req.SportDays = 30
	}

	now := s.coros.nowFn().In(s.docs.tz)
	startDate := now.AddDate(0, 0, -(req.SportDays - 1)).Format("20060102")
	endDate := now.Format("20060102")

	syncCtx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()

	sleepText, err := s.coros.callTool(syncCtx, "querySleepOverview", map[string]any{
		"days": req.SleepDays,
	})
	if err != nil {
		if errors.Is(err, errCorosAuthExpired) {
			errorJSON(w, http.StatusConflict, "coros_auth_expired")
			return
		}
		if errors.Is(err, errCorosUnavailable) {
			errorJSON(w, http.StatusServiceUnavailable, "coros_sync_unavailable")
			return
		}
		errorJSON(w, http.StatusBadGateway, "coros_sleep_fetch_failed")
		return
	}

	sportText, err := s.coros.callTool(syncCtx, "querySportRecords", map[string]any{
		"startDate": startDate,
		"endDate":   endDate,
		"limit":     20,
	})
	if err != nil {
		if errors.Is(err, errCorosAuthExpired) {
			errorJSON(w, http.StatusConflict, "coros_auth_expired")
			return
		}
		errorJSON(w, http.StatusBadGateway, "coros_sport_fetch_failed")
		return
	}

	sleepItems := parseCorosSleepOverview(sleepText)
	runItems, rideItems := parseCorosSportRecords(sportText)

	analyticsFileMu.Lock()
	defer analyticsFileMu.Unlock()

	sleepAdded, sleepUpdated, latestSleep, err := s.docs.mergeCorosSleepLocked(sleepItems)
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "coros_sleep_write_failed")
		return
	}
	runAdded, runUpdated, latestRun, err := s.docs.mergeCorosRunningLocked(runItems)
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "coros_running_write_failed")
		return
	}
	rideAdded, rideUpdated, err := s.docs.mergeCorosCyclingLocked(rideItems)
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "coros_cycling_write_failed")
		return
	}

	writeJSON(w, http.StatusOK, corosSyncResult{
		OK:                true,
		SleepSynced:       len(sleepItems),
		SleepAdded:        sleepAdded,
		SleepUpdated:      sleepUpdated,
		RunningSynced:     len(runItems),
		RunningAdded:      runAdded,
		RunningUpdated:    runUpdated,
		CyclingSynced:     len(rideItems),
		CyclingAdded:      rideAdded,
		CyclingUpdated:    rideUpdated,
		LatestSleepDate:   latestSleep,
		LatestRunningDate: latestRun,
		SyncedAt:          now.Format(time.RFC3339),
	})
}
