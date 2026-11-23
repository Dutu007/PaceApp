package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math"
	"math/rand"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type proactiveState struct {
	Enabled            bool       `json:"enabled"`
	Intensity          string     `json:"intensity"`
	QuietStart         string     `json:"quiet_start"`
	QuietEnd           string     `json:"quiet_end"`
	LabFocusProtect    bool       `json:"lab_focus_protect"`
	StrictSupervision  bool       `json:"strict_supervision"`
	TodayDate          string     `json:"today_date"`
	TodayBudget        int        `json:"today_budget"`
	TodayMood          string     `json:"today_mood"`
	SentToday          int        `json:"sent_today"`
	StrictOverrideUsed int        `json:"strict_override_used"`
	UnansweredCount    int        `json:"unanswered_count"`
	AccumulatedUrge    float64    `json:"accumulated_urge"`
	LastSentAt         *time.Time `json:"last_sent_at,omitempty"`
	LastEvalAt         *time.Time `json:"last_eval_at,omitempty"`
	NextEvalAt         *time.Time `json:"next_eval_at,omitempty"`
	LastDecisionReason string     `json:"last_decision_reason,omitempty"`
}

func defaultProactiveState() proactiveState {
	return proactiveState{
		Enabled:           true,
		Intensity:         "balanced",
		QuietStart:        "23:30",
		QuietEnd:          "08:30",
		LabFocusProtect:   true,
		StrictSupervision: true,
		TodayBudget:       3,
		TodayMood:         "cozy",
	}
}

type proactiveStore interface {
	Get(ctx context.Context) (proactiveState, error)
	Save(ctx context.Context, st proactiveState) error
}

type pgProactiveStore struct {
	pool *pgxpool.Pool
}

func (p *pgProactiveStore) Get(ctx context.Context) (proactiveState, error) {
	if p == nil || p.pool == nil {
		return defaultProactiveState(), fmt.Errorf("proactive_db_unavailable")
	}
	_, _ = p.pool.Exec(ctx, `insert into pace_proactive_state (id) values (1) on conflict (id) do nothing`)
	var st proactiveState
	err := p.pool.QueryRow(
		ctx,
		`select enabled, intensity, quiet_start, quiet_end, lab_focus_protect, strict_supervision,
		        today_date, today_budget, today_mood, sent_today, strict_override_used, unanswered_count,
		        accumulated_urge, last_sent_at, last_eval_at, next_eval_at, last_decision_reason
		   from pace_proactive_state where id = 1`,
	).Scan(
		&st.Enabled, &st.Intensity, &st.QuietStart, &st.QuietEnd,
		&st.LabFocusProtect, &st.StrictSupervision, &st.TodayDate,
		&st.TodayBudget, &st.TodayMood, &st.SentToday, &st.StrictOverrideUsed,
		&st.UnansweredCount, &st.AccumulatedUrge, &st.LastSentAt,
		&st.LastEvalAt, &st.NextEvalAt, &st.LastDecisionReason,
	)
	if err == pgx.ErrNoRows {
		return defaultProactiveState(), nil
	}
	if err != nil {
		return defaultProactiveState(), err
	}
	st.Intensity = normalizeIntensity(st.Intensity)
	return st, nil
}

func (p *pgProactiveStore) Save(ctx context.Context, st proactiveState) error {
	if p == nil || p.pool == nil {
		return fmt.Errorf("proactive_db_unavailable")
	}
	st.Intensity = normalizeIntensity(st.Intensity)
	_, err := p.pool.Exec(
		ctx,
		`insert into pace_proactive_state (
			id, enabled, intensity, quiet_start, quiet_end, lab_focus_protect, strict_supervision,
			today_date, today_budget, today_mood, sent_today, strict_override_used, unanswered_count,
			accumulated_urge, last_sent_at, last_eval_at, next_eval_at, last_decision_reason, updated_at
		) values (
			1, $1, $2, $3, $4, $5, $6,
			$7, $8, $9, $10, $11, $12,
			$13, $14, $15, $16, $17, now()
		)
		on conflict (id) do update set
			enabled = excluded.enabled,
			intensity = excluded.intensity,
			quiet_start = excluded.quiet_start,
			quiet_end = excluded.quiet_end,
			lab_focus_protect = excluded.lab_focus_protect,
			strict_supervision = excluded.strict_supervision,
			today_date = excluded.today_date,
			today_budget = excluded.today_budget,
			today_mood = excluded.today_mood,
			sent_today = excluded.sent_today,
			strict_override_used = excluded.strict_override_used,
			unanswered_count = excluded.unanswered_count,
			accumulated_urge = excluded.accumulated_urge,
			last_sent_at = excluded.last_sent_at,
			last_eval_at = excluded.last_eval_at,
			next_eval_at = excluded.next_eval_at,
			last_decision_reason = excluded.last_decision_reason,
			updated_at = now()`,
		st.Enabled, st.Intensity, st.QuietStart, st.QuietEnd, st.LabFocusProtect, st.StrictSupervision,
		st.TodayDate, st.TodayBudget, st.TodayMood, st.SentToday, st.StrictOverrideUsed, st.UnansweredCount,
		st.AccumulatedUrge, st.LastSentAt, st.LastEvalAt, st.NextEvalAt, truncateRunes(st.LastDecisionReason, 500),
	)
	return err
}

func normalizeIntensity(raw string) string {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "quiet":
		return "quiet"
	case "lively":
		return "lively"
	default:
		return "balanced"
	}
}

func parseClockMinutes(raw string) (int, bool) {
	parts := strings.Split(strings.TrimSpace(raw), ":")
	if len(parts) != 2 {
		return 0, false
	}
	h, err1 := strconv.Atoi(parts[0])
	m, err2 := strconv.Atoi(parts[1])
	if err1 != nil || err2 != nil || h < 0 || h > 23 || m < 0 || m > 59 {
		return 0, false
	}
	return h*60 + m, true
}

func inQuietHours(now time.Time, startStr, endStr string) bool {
	startMin, ok1 := parseClockMinutes(startStr)
	endMin, ok2 := parseClockMinutes(endStr)
	if !ok1 || !ok2 || startMin == endMin {
		return false
	}
	cur := now.Hour()*60 + now.Minute()
	if startMin < endMin {
		return cur >= startMin && cur < endMin
	}
	return cur >= startMin || cur < endMin
}

func estimateActiveHours(startStr, endStr string) float64 {
	startMin, ok1 := parseClockMinutes(startStr)
	endMin, ok2 := parseClockMinutes(endStr)
	if !ok1 || !ok2 || startMin == endMin {
		return 15.0
	}
	var quiet int
	if startMin < endMin {
		quiet = endMin - startMin
	} else {
		quiet = (24*60 - startMin) + endMin
	}
	active := float64(24*60-quiet) / 60.0
	if active < 4.0 {
		return 4.0
	}
	return active
}

func rollDailyBudgetAndMood(intensity string, wd time.Weekday, rng *rand.Rand) (int, string) {
	var pool []int
	switch normalizeIntensity(intensity) {
	case "quiet":
		pool = []int{0, 1, 1, 2, 2, 3}
	case "lively":
		pool = []int{2, 3, 4, 4, 5, 6, 6, 7, 8}
	default:
		// Wide variance so some days are quiet (0-1) and others chatty (5-6)
		pool = []int{0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6}
	}
	budget := pool[rng.Intn(len(pool))]

	var mood string
	if budget <= 0 {
		mood = "quiet"
	} else if budget == 1 {
		moods := []string{"quiet", "cozy"}
		mood = moods[rng.Intn(len(moods))]
	} else if wd >= time.Monday && wd <= time.Friday {
		moods := []string{"cozy", "energetic", "strict", "cozy", "strict"}
		mood = moods[rng.Intn(len(moods))]
	} else {
		moods := []string{"cozy", "energetic", "cozy", "strict"}
		mood = moods[rng.Intn(len(moods))]
	}
	return budget, mood
}

type impulseEvalInput struct {
	Now               time.Time
	State             proactiveState
	Life              proactiveLifeSnapshot
	LastUserMessageAt *time.Time
	Force             bool
}

type impulseEvalResult struct {
	Allow       bool
	StrictReady bool
	BlockedBy   string
	HitProb     float64
	EventBoost  float64
	NextUrge    float64
}

func evaluateGuardrailsAndImpulse(in impulseEvalInput, rng *rand.Rand) impulseEvalResult {
	st := in.State
	snap := in.Life
	now := in.Now
	strictCandidate := st.StrictSupervision && (snap.LateBedtime || snap.SlackingSignal || snap.OverworkSignal || snap.DaysSinceLastRun >= 4)

	if in.Force {
		return impulseEvalResult{
			Allow:       true,
			StrictReady: strictCandidate,
			HitProb:     1.0,
			EventBoost:  1.0,
			NextUrge:    st.AccumulatedUrge,
		}
	}

	if !st.Enabled {
		return impulseEvalResult{BlockedBy: "disabled", NextUrge: st.AccumulatedUrge}
	}
	if inQuietHours(now, st.QuietStart, st.QuietEnd) {
		return impulseEvalResult{BlockedBy: "quiet_hours", NextUrge: st.AccumulatedUrge}
	}
	if st.UnansweredCount >= 2 {
		return impulseEvalResult{BlockedBy: "unanswered_backoff", NextUrge: st.AccumulatedUrge}
	}
	if in.LastUserMessageAt != nil && now.Sub(*in.LastUserMessageAt) < 45*time.Minute {
		return impulseEvalResult{BlockedBy: "recent_user_chat", NextUrge: st.AccumulatedUrge}
	}

	minCooldown := 50.0
	switch normalizeIntensity(st.Intensity) {
	case "quiet":
		minCooldown = 90.0
	case "lively":
		minCooldown = 30.0
	}
	if st.LastSentAt != nil && now.Sub(*st.LastSentAt).Minutes() < minCooldown {
		return impulseEvalResult{BlockedBy: "cooldown", NextUrge: st.AccumulatedUrge}
	}
	if st.LabFocusProtect && snap.LabActive && !snap.OverworkSignal {
		return impulseEvalResult{BlockedBy: "lab_focus_protect", NextUrge: st.AccumulatedUrge}
	}

	budgetExceeded := st.SentToday >= st.TodayBudget
	if budgetExceeded {
		canStrictOverride := strictCandidate && st.StrictOverrideUsed == 0 && st.UnansweredCount == 0
		if !canStrictOverride {
			return impulseEvalResult{BlockedBy: "daily_budget_reached", NextUrge: st.AccumulatedUrge}
		}
	}

	activeHours := estimateActiveHours(st.QuietStart, st.QuietEnd)
	effectiveBudget := st.TodayBudget
	if effectiveBudget < 1 {
		effectiveBudget = 1
	}
	baseRate := float64(effectiveBudget) / activeHours

	moodFactor := 1.0
	switch st.TodayMood {
	case "energetic":
		moodFactor = 1.25
	case "strict":
		moodFactor = 1.10
	case "quiet":
		moodFactor = 0.65
	}

	eventBoost := 1.0
	if snap.MinutesSinceLastLabOut >= 5 && snap.MinutesSinceLastLabOut <= 45 {
		eventBoost = 2.2
	} else if snap.OverworkSignal || snap.SlackingSignal || snap.LateBedtime {
		eventBoost = 1.9
	} else if snap.RanToday && st.SentToday == 0 {
		eventBoost = 1.5
	}

	lambda := baseRate * moodFactor * eventBoost
	if st.UnansweredCount == 1 {
		lambda *= 0.45
	}

	dtHours := 0.75
	if st.LastEvalAt != nil {
		dtHours = now.Sub(*st.LastEvalAt).Hours()
	} else if st.LastSentAt != nil {
		dtHours = now.Sub(*st.LastSentAt).Hours()
	}
	if dtHours < 0.35 {
		dtHours = 0.35
	} else if dtHours > 3.0 {
		dtHours = 3.0
	}

	pRaw := 1.0 - math.Exp(-lambda*dtHours)
	p := pRaw + st.AccumulatedUrge
	if p < 0.06 {
		p = 0.06
	} else if p > 0.92 {
		p = 0.92
	}

	if rng.Float64() <= p {
		return impulseEvalResult{
			Allow:       true,
			StrictReady: strictCandidate,
			HitProb:     p,
			EventBoost:  eventBoost,
			NextUrge:    st.AccumulatedUrge,
		}
	}

	nextUrge := math.Min(0.45, st.AccumulatedUrge+0.06)
	return impulseEvalResult{
		Allow:       false,
		StrictReady: strictCandidate,
		BlockedBy:   "poisson_miss",
		HitProb:     p,
		EventBoost:  eventBoost,
		NextUrge:    nextUrge,
	}
}

type proactiveEngine struct {
	mu    sync.Mutex
	srv   *server
	store proactiveStore
	rng   *rand.Rand
	nowFn func() time.Time
}

func newProactiveEngine(srv *server, store proactiveStore) *proactiveEngine {
	var tz *time.Location
	if srv != nil && srv.docs != nil && srv.docs.tz != nil {
		tz = srv.docs.tz
	} else {
		loc, err := time.LoadLocation("Asia/Shanghai")
		if err != nil {
			loc = time.FixedZone("CST", 8*3600)
		}
		tz = loc
	}
	return &proactiveEngine{
		srv:   srv,
		store: store,
		rng:   rand.New(rand.NewSource(time.Now().UnixNano())),
		nowFn: func() time.Time { return time.Now().In(tz) },
	}
}

func (e *proactiveEngine) Start(ctx context.Context) {
	if e == nil || e.store == nil {
		return
	}
	ticker := time.NewTicker(60 * time.Second)
	defer ticker.Stop()

	e.ensureInitialSchedule(ctx)

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			evalCtx, cancel := context.WithTimeout(ctx, 40*time.Second)
			if err := e.evaluateIfDue(evalCtx); err != nil {
				log.Printf("proactive check skipped: %v", err)
			}
			cancel()
		}
	}
}

func (e *proactiveEngine) ensureInitialSchedule(ctx context.Context) {
	e.mu.Lock()
	defer e.mu.Unlock()
	now := e.nowFn()
	st, err := e.store.Get(ctx)
	if err != nil {
		return
	}
	changed := e.ensureTodayRolledLocked(&st, now)
	if st.NextEvalAt == nil {
		next := now.Add(e.nextSleepDuration(st.Intensity, 0))
		st.NextEvalAt = &next
		changed = true
	}
	if changed {
		_ = e.store.Save(ctx, st)
	}
}

func (e *proactiveEngine) ensureTodayRolledLocked(st *proactiveState, now time.Time) bool {
	todayKey := now.Format("2006-01-02")
	if st.TodayDate == todayKey && st.TodayMood != "" {
		return false
	}
	budget, mood := rollDailyBudgetAndMood(st.Intensity, now.Weekday(), e.rng)
	st.TodayDate = todayKey
	st.TodayBudget = budget
	st.TodayMood = mood
	st.SentToday = 0
	st.StrictOverrideUsed = 0
	return true
}

func (e *proactiveEngine) nextSleepDuration(intensity string, suggestedMinutes int) time.Duration {
	if suggestedMinutes >= 25 && suggestedMinutes <= 180 {
		jitter := 0.85 + e.rng.Float64()*0.30
		mins := int(math.Round(float64(suggestedMinutes) * jitter))
		if mins < 25 {
			mins = 25
		} else if mins > 180 {
			mins = 180
		}
		return time.Duration(mins) * time.Minute
	}
	baseMin, baseMax := 35.0, 115.0
	switch normalizeIntensity(intensity) {
	case "quiet":
		baseMin, baseMax = 65.0, 155.0
	case "lively":
		baseMin, baseMax = 25.0, 75.0
	}
	mins := int(math.Round(baseMin + e.rng.Float64()*(baseMax-baseMin)))
	return time.Duration(mins) * time.Minute
}

func (e *proactiveEngine) onUserActivity(ctx context.Context) {
	if e == nil || e.store == nil {
		return
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	now := e.nowFn()
	st, err := e.store.Get(ctx)
	if err != nil {
		return
	}
	changed := e.ensureTodayRolledLocked(&st, now)
	if st.UnansweredCount != 0 {
		st.UnansweredCount = 0
		changed = true
	}
	if changed {
		_ = e.store.Save(ctx, st)
	}
}

func (e *proactiveEngine) evaluateIfDue(ctx context.Context) error {
	e.mu.Lock()
	now := e.nowFn()
	st, err := e.store.Get(ctx)
	if err != nil {
		e.mu.Unlock()
		return err
	}
	rolled := e.ensureTodayRolledLocked(&st, now)
	if st.NextEvalAt != nil && now.Before(*st.NextEvalAt) {
		if rolled {
			_ = e.store.Save(ctx, st)
		}
		e.mu.Unlock()
		return nil
	}
	e.mu.Unlock()

	_, _, err = e.runEvaluation(ctx, false)
	return err
}

func (e *proactiveEngine) runEvaluation(ctx context.Context, force bool) (*pushNotification, string, error) {
	if e == nil || e.store == nil || e.srv == nil {
		return nil, "unavailable", fmt.Errorf("proactive_unavailable")
	}

	e.mu.Lock()
	now := e.nowFn()
	st, err := e.store.Get(ctx)
	if err != nil {
		e.mu.Unlock()
		return nil, "db_error", err
	}
	e.ensureTodayRolledLocked(&st, now)
	life := e.srv.docs.buildLifeSnapshot(now)
	e.mu.Unlock()

	lastUserAt, recentChat := e.fetchRecentHermesContext(ctx, now)
	recentPush := e.fetchRecentNotifications(ctx)

	e.mu.Lock()
	now = e.nowFn()
	st, err = e.store.Get(ctx)
	if err != nil {
		e.mu.Unlock()
		return nil, "db_error", err
	}
	e.ensureTodayRolledLocked(&st, now)
	evalRes := evaluateGuardrailsAndImpulse(impulseEvalInput{
		Now:               now,
		State:             st,
		Life:              life,
		LastUserMessageAt: lastUserAt,
		Force:             force,
	}, e.rng)

	if !evalRes.Allow {
		st.LastEvalAt = &now
		st.AccumulatedUrge = evalRes.NextUrge
		next := now.Add(e.nextSleepDuration(st.Intensity, 0))
		st.NextEvalAt = &next
		st.LastDecisionReason = "blocked:" + evalRes.BlockedBy
		_ = e.store.Save(ctx, st)
		e.mu.Unlock()
		return nil, st.LastDecisionReason, nil
	}
	stSnapshot := st
	e.mu.Unlock()

	decision, decErr := e.decideProactiveMessage(ctx, stSnapshot, life, recentChat, recentPush, evalRes.StrictReady, force, now)

	e.mu.Lock()
	defer e.mu.Unlock()
	now = e.nowFn()
	st, err = e.store.Get(ctx)
	if err != nil {
		return nil, "db_error", err
	}
	e.ensureTodayRolledLocked(&st, now)
	st.LastEvalAt = &now

	if decErr != nil {
		next := now.Add(e.nextSleepDuration(st.Intensity, 0))
		st.NextEvalAt = &next
		st.LastDecisionReason = "llm_error:" + decErr.Error()
		_ = e.store.Save(ctx, st)
		return nil, st.LastDecisionReason, decErr
	}

	if !decision.Speak || strings.TrimSpace(decision.Message) == "" {
		next := now.Add(e.nextSleepDuration(st.Intensity, decision.NextSleepMinutes))
		st.NextEvalAt = &next
		if !force {
			st.AccumulatedUrge = math.Min(0.45, st.AccumulatedUrge+0.04)
		}
		reason := strings.TrimSpace(decision.Reason)
		if reason == "" {
			reason = "silent"
		}
		st.LastDecisionReason = "silent:" + reason
		_ = e.store.Save(ctx, st)
		return nil, st.LastDecisionReason, nil
	}

	category := "proactive_chat"
	title := "小洛"
	if decision.Mode == "strict_supervisor" {
		category = "proactive_strict"
		title = "小洛 · 督学提醒"
	}

	item, pushErr := e.srv.createAndBroadcastNotification(ctx, createNotificationRequest{
		Title:    title,
		Body:     strings.TrimSpace(decision.Message),
		Category: category,
		Source:   "proactive",
	})
	if pushErr != nil {
		next := now.Add(e.nextSleepDuration(st.Intensity, 0))
		st.NextEvalAt = &next
		st.LastDecisionReason = "push_error:" + pushErr.Error()
		_ = e.store.Save(ctx, st)
		return nil, st.LastDecisionReason, pushErr
	}

	st.SentToday++
	if !force && evalRes.StrictReady && st.SentToday > st.TodayBudget {
		st.StrictOverrideUsed++
	}
	if !force {
		st.UnansweredCount++
	}
	st.AccumulatedUrge = 0
	st.LastSentAt = &now
	next := now.Add(e.nextSleepDuration(st.Intensity, decision.NextSleepMinutes))
	st.NextEvalAt = &next
	reason := strings.TrimSpace(decision.Reason)
	if reason == "" {
		reason = decision.Mode
	}
	st.LastDecisionReason = "sent:" + reason
	_ = e.store.Save(ctx, st)
	return &item, st.LastDecisionReason, nil
}

type chatTurnSnippet struct {
	Role string
	Text string
}

var (
	goSteerOpenRegex      = regexp.MustCompile(`(?m)^\[OUT-OF-BAND USER MESSAGE[^\]]*\]\r?\n?`)
	goSteerCloseRegex     = regexp.MustCompile(`\r?\n?\[/OUT-OF-BAND USER MESSAGE\]\s*$`)
	goProactiveCtxRegex   = regexp.MustCompile(`(?s)^\[PACE_PROACTIVE_CONTEXT\].*?\[/PACE_PROACTIVE_CONTEXT\]\s*`)
	goMarkdownFenceRegex  = regexp.MustCompile("(?s)^```(?:json)?\\s*(.*?)\\s*```$")
)

func cleanSteerAndContextText(raw string) string {
	s := strings.TrimSpace(raw)
	s = goProactiveCtxRegex.ReplaceAllString(s, "")
	s = strings.TrimSpace(s)
	if strings.HasPrefix(s, "[OUT-OF-BAND USER MESSAGE") {
		s = goSteerOpenRegex.ReplaceAllString(s, "")
		s = goSteerCloseRegex.ReplaceAllString(s, "")
	}
	return strings.TrimSpace(s)
}

func stripJSONCodeFence(raw string) string {
	s := strings.TrimSpace(raw)
	if m := goMarkdownFenceRegex.FindStringSubmatch(s); m != nil {
		return strings.TrimSpace(m[1])
	}
	if start := strings.Index(s, "{"); start >= 0 {
		if end := strings.LastIndex(s, "}"); end > start {
			return s[start : end+1]
		}
	}
	return s
}

func (e *proactiveEngine) fetchRecentHermesContext(ctx context.Context, now time.Time) (*time.Time, []chatTurnSnippet) {
	if e.srv == nil || e.srv.hermes == nil {
		return nil, nil
	}
	reqCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()

	status, sessRaw, err := e.srv.hermes.doJSON(reqCtx, http.MethodGet, "/api/sessions?source=api_server&limit=3", nil)
	if err != nil || status < 200 || status >= 300 {
		return nil, nil
	}
	var sessList struct {
		Data []struct {
			ID         string   `json:"id"`
			LastActive *float64 `json:"last_active"`
		} `json:"data"`
	}
	if json.Unmarshal(sessRaw, &sessList) != nil || len(sessList.Data) == 0 {
		return nil, nil
	}

	latestSess := sessList.Data[0]
	var lastUserAt *time.Time
	status2, msgRaw, err := e.srv.hermes.doJSON(reqCtx, http.MethodGet, "/api/sessions/"+latestSess.ID+"/messages?limit=12&order=latest", nil)
	if err == nil && status2 >= 200 && status2 < 300 {
		var msgList struct {
			Data []struct {
				Role      string          `json:"role"`
				Content   json.RawMessage `json:"content"`
				Timestamp *float64        `json:"timestamp"`
			} `json:"data"`
		}
		if json.Unmarshal(msgRaw, &msgList) == nil {
			out := make([]chatTurnSnippet, 0, 8)
			for _, m := range msgList.Data {
				if m.Role != "user" && m.Role != "assistant" {
					continue
				}
				txt := extractHermesRawContentText(m.Content)
				if m.Role == "user" {
					txt = cleanSteerAndContextText(txt)
					if m.Timestamp != nil && *m.Timestamp > 0 {
						sec := int64(*m.Timestamp)
						nsec := int64((*m.Timestamp - float64(sec)) * 1e9)
						t := time.Unix(sec, nsec).In(now.Location())
						if lastUserAt == nil || t.After(*lastUserAt) {
							tt := t
							lastUserAt = &tt
						}
					}
				}
				txt = strings.TrimSpace(txt)
				if txt == "" {
					continue
				}
				out = append(out, chatTurnSnippet{Role: m.Role, Text: truncateRunes(txt, 180)})
			}
			if len(out) > 8 {
				out = out[len(out)-8:]
			}
			return lastUserAt, out
		}
	}
	if lastUserAt == nil && latestSess.LastActive != nil && *latestSess.LastActive > 0 {
		sec := int64(*latestSess.LastActive)
		t := time.Unix(sec, 0).In(now.Location())
		lastUserAt = &t
	}
	return lastUserAt, nil
}

func extractHermesRawContentText(raw json.RawMessage) string {
	trimmed := strings.TrimSpace(string(raw))
	if trimmed == "" || trimmed == "null" {
		return ""
	}
	var asStr string
	if json.Unmarshal(raw, &asStr) == nil {
		return asStr
	}
	var blocks []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	}
	if json.Unmarshal(raw, &blocks) == nil {
		parts := make([]string, 0, len(blocks))
		for _, b := range blocks {
			if (b.Type == "text" || b.Type == "") && strings.TrimSpace(b.Text) != "" {
				parts = append(parts, strings.TrimSpace(b.Text))
			}
		}
		return strings.Join(parts, "\n")
	}
	return ""
}

func (e *proactiveEngine) fetchRecentNotifications(ctx context.Context) []pushNotification {
	if e.srv == nil || e.srv.notifications == nil {
		return nil
	}
	items, _, err := e.srv.notifications.List(ctx, 5, false, 0)
	if err != nil {
		return nil
	}
	return items
}

type llmProactiveDecision struct {
	Speak            bool   `json:"speak"`
	Mode             string `json:"mode"`
	Message          string `json:"message"`
	NextSleepMinutes int    `json:"next_sleep_minutes"`
	Reason           string `json:"reason"`
}

func (e *proactiveEngine) readOptionalExcerpt(path string, maxRunes int) string {
	if strings.TrimSpace(path) == "" {
		return ""
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return truncateRunes(strings.TrimSpace(string(raw)), maxRunes)
}

func (e *proactiveEngine) decideProactiveMessage(
	ctx context.Context,
	st proactiveState,
	life proactiveLifeSnapshot,
	recentChat []chatTurnSnippet,
	recentPush []pushNotification,
	strictReady bool,
	force bool,
	now time.Time,
) (llmProactiveDecision, error) {
	if e.srv == nil || e.srv.llm == nil {
		return llmProactiveDecision{}, fmt.Errorf("llm_not_configured")
	}

	var personaText, habitText, memText string
	if e.srv.docs != nil {
		if e.srv.docs.assistantDir != "" {
			personaText = e.readOptionalExcerpt(filepath.Join(e.srv.docs.assistantDir, "notes", "小洛人设.md"), 1200)
			habitText = e.readOptionalExcerpt(filepath.Join(e.srv.docs.assistantDir, "notes", "科研习惯清单.md"), 800)
		}
		memText = e.readOptionalExcerpt(e.srv.docs.memoryFile, 900)
	}

	sysPrompt := `你是"小洛"，曾先生（曾德铭）手机里的专属伙伴，兼具三重身份：靠谱的个人助手 + 养成系电子女儿/陪伴伙伴 + 严厉的学习与生活习惯监督者。
现在系统后台在没有用户发消息时随机唤醒了你，让你决定"此时要不要主动给曾先生发一条短消息/闲聊冒泡/督学提醒"。

严格规则：
1. 必须输出严格 JSON：{"speak": true/false, "mode": "companion|strict_supervisor|assistant", "message": "...", "next_sleep_minutes": 90, "reason": "..."}。
2. 语气分寸：
   - companion（日常闲聊/关心）：1-2 句，自然柔和略带灵气和轻微黏人感，像是养在手机里的小家伙突然冒个泡；绝对不要是恋爱暧昧语气，**绝不评论或提及女友小鱼**。
   - strict_supervisor（严厉督学/作息监督）：若检测到熬夜晚睡、睡眠不足、下午至晚上仍几乎没去实验室打卡、连续久坐超 3 小时或连续 4 天以上未运动且当前适合提醒，必须收起软萌，用直接、略带严肃督促感的语气一两句点破，并给出一个具体微行动要求；但不能生硬说教长篇大论。
   - assistant（轻助手衔接）：顺手延续他正在关注的论文/比赛/日程等正事。
3. 硬性格式禁忌：
   - 绝对不要以报时开头（不要写"现在是14:20"）；
   - 禁用一切情绪类 emoji（如😎💪😏😂😅🤨😈🎉👏🔥）；
   - 不要每句末尾都用反问句追问；
   - 绝对不能编造下面未给出的睡眠时长、打卡分钟或公里数；
   - 避免与"最近几条已发出通知"重复或撞车；
   - 如果此时确实没什么值得打扰的话，把 speak 设为 false（若 prompt 注明用户手动点击了强制测试，则必须给出一条符合当前真实状态的高质量冒泡消息，speak 设为 true）。`

	var ub strings.Builder
	ub.WriteString(fmt.Sprintf("当前时间：%s（%s）\n", now.Format("2006-01-02 15:04"), weekdayCN(now.Weekday())))
	ub.WriteString(fmt.Sprintf("今日状态：今日心情基调=%s，已主动冒泡 %d/%d 次，档位=%s\n", st.TodayMood, st.SentToday, st.TodayBudget, st.Intensity))
	if strictReady {
		ub.WriteString("督学信号：当前触发了作息/学习/运动督学检查条件，请重点考虑是否切换 strict_supervisor 严厉提醒。\n")
	}
	if force {
		ub.WriteString("特别说明：曾先生刚刚在设置里点击了「让小洛现在冒个泡」立即体验触发，请务必给出一条贴合此时情景的发言（speak 设为 true）。\n")
	}

	ub.WriteString("\n【当前客观生活与学习数据】\n")
	ub.WriteString(fmt.Sprintf("- 实验室打卡：进行中=%v（本次已待 %d 分钟），今日累计 %d 分钟，连续打卡 %d 天", life.LabActive, life.LabActiveMinutes, life.TodayLabMinutes, life.LabStreakDays))
	if life.MinutesSinceLastLabOut >= 0 {
		ub.WriteString(fmt.Sprintf("，距上次签退已 %d 分钟", life.MinutesSinceLastLabOut))
	}
	ub.WriteString("\n")
	if life.HasTodaySleep {
		scoreStr := "无评分"
		if life.TodaySleepScore != nil {
			scoreStr = fmt.Sprintf("%d 分", *life.TodaySleepScore)
		}
		ub.WriteString(fmt.Sprintf("- 今日睡眠记录：时长 %d 分钟，入睡 %s，醒来 %s，%s，熬夜/缺觉信号=%v\n",
			life.TodaySleepMinutes, life.TodaySleepBedtime, life.TodaySleepWakeTime, scoreStr, life.LateBedtime))
	} else {
		ub.WriteString("- 今日睡眠记录：暂无今日数据（禁止猜测昨晚睡眠）\n")
	}
	ub.WriteString(fmt.Sprintf("- 跑步运动：今日是否已跑=%v（%.2f km），距上次有记录已过 %d 天\n", life.RanToday, life.TodayRunKm, life.DaysSinceLastRun))
	ub.WriteString(fmt.Sprintf("- 行为判定：下午晚间仍未进实验室信号=%v，连续在实验室久坐超3小时=%v\n", life.SlackingSignal, life.OverworkSignal))

	if len(recentChat) > 0 {
		ub.WriteString("\n【最近对话摘录】\n")
		for _, turn := range recentChat {
			roleLabel := "曾先生"
			if turn.Role == "assistant" {
				roleLabel = "小洛"
			}
			ub.WriteString(fmt.Sprintf("- %s：%s\n", roleLabel, turn.Text))
		}
	}
	if len(recentPush) > 0 {
		ub.WriteString("\n【最近已发出的主动消息（避免重复）】\n")
		for _, p := range recentPush {
			ub.WriteString(fmt.Sprintf("- [%s] %s: %s\n", p.Category, p.Title, truncateRunes(p.Body, 100)))
		}
	}
	if personaText != "" {
		ub.WriteString("\n【小洛人设参考】\n")
		ub.WriteString(personaText)
		ub.WriteString("\n")
	}
	if habitText != "" {
		ub.WriteString("\n【科研习惯参考】\n")
		ub.WriteString(habitText)
		ub.WriteString("\n")
	}
	if memText != "" {
		ub.WriteString("\n【记忆摘要】\n")
		ub.WriteString(memText)
		ub.WriteString("\n")
	}

	raw, err := e.srv.llm.completeChat(ctx, sysPrompt, ub.String(), 0.75, 360, true)
	if err != nil {
		return llmProactiveDecision{}, err
	}
	cleaned := stripJSONCodeFence(raw)
	var dec llmProactiveDecision
	if err := json.Unmarshal([]byte(cleaned), &dec); err != nil {
		return llmProactiveDecision{}, fmt.Errorf("invalid_llm_json: %w", err)
	}
	if dec.Mode != "strict_supervisor" && dec.Mode != "assistant" {
		dec.Mode = "companion"
	}
	dec.Message = truncateRunes(strings.TrimSpace(dec.Message), 600)
	return dec, nil
}

func (s *server) getProactiveConfig(w http.ResponseWriter, r *http.Request) {
	if s.proactive == nil || s.proactive.store == nil {
		errorJSON(w, http.StatusServiceUnavailable, "proactive_unavailable")
		return
	}
	s.proactive.mu.Lock()
	defer s.proactive.mu.Unlock()
	now := s.proactive.nowFn()
	st, err := s.proactive.store.Get(r.Context())
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "proactive_read_failed")
		return
	}
	if s.proactive.ensureTodayRolledLocked(&st, now) {
		_ = s.proactive.store.Save(r.Context(), st)
	}
	writeJSON(w, http.StatusOK, st)
}

func (s *server) updateProactiveConfig(w http.ResponseWriter, r *http.Request) {
	if s.proactive == nil || s.proactive.store == nil {
		errorJSON(w, http.StatusServiceUnavailable, "proactive_unavailable")
		return
	}
	var req struct {
		Enabled           *bool   `json:"enabled"`
		Intensity         *string `json:"intensity"`
		QuietStart        *string `json:"quiet_start"`
		QuietEnd          *string `json:"quiet_end"`
		LabFocusProtect   *bool   `json:"lab_focus_protect"`
		StrictSupervision *bool   `json:"strict_supervision"`
	}
	if !decodeJSON(w, r, &req) {
		return
	}

	s.proactive.mu.Lock()
	defer s.proactive.mu.Unlock()
	now := s.proactive.nowFn()
	st, err := s.proactive.store.Get(r.Context())
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "proactive_read_failed")
		return
	}
	s.proactive.ensureTodayRolledLocked(&st, now)

	intensityChanged := false
	if req.Enabled != nil {
		st.Enabled = *req.Enabled
	}
	if req.Intensity != nil {
		nextInt := normalizeIntensity(*req.Intensity)
		if nextInt != st.Intensity {
			st.Intensity = nextInt
			intensityChanged = true
		}
	}
	if req.QuietStart != nil {
		if _, ok := parseClockMinutes(*req.QuietStart); !ok {
			errorJSON(w, http.StatusBadRequest, "invalid_quiet_start")
			return
		}
		st.QuietStart = strings.TrimSpace(*req.QuietStart)
	}
	if req.QuietEnd != nil {
		if _, ok := parseClockMinutes(*req.QuietEnd); !ok {
			errorJSON(w, http.StatusBadRequest, "invalid_quiet_end")
			return
		}
		st.QuietEnd = strings.TrimSpace(*req.QuietEnd)
	}
	if req.LabFocusProtect != nil {
		st.LabFocusProtect = *req.LabFocusProtect
	}
	if req.StrictSupervision != nil {
		st.StrictSupervision = *req.StrictSupervision
	}
	if intensityChanged {
		budget, mood := rollDailyBudgetAndMood(st.Intensity, now.Weekday(), s.proactive.rng)
		if budget < st.SentToday {
			budget = st.SentToday
		}
		st.TodayBudget = budget
		st.TodayMood = mood
	}
	if st.Enabled && st.NextEvalAt == nil {
		next := now.Add(s.proactive.nextSleepDuration(st.Intensity, 0))
		st.NextEvalAt = &next
	}
	if err := s.proactive.store.Save(r.Context(), st); err != nil {
		errorJSON(w, http.StatusInternalServerError, "proactive_save_failed")
		return
	}
	writeJSON(w, http.StatusOK, st)
}

func (s *server) triggerProactiveCheck(w http.ResponseWriter, r *http.Request) {
	if s.proactive == nil || s.proactive.store == nil {
		errorJSON(w, http.StatusServiceUnavailable, "proactive_unavailable")
		return
	}
	var req struct {
		Force bool `json:"force"`
	}
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<10)).Decode(&req)

	evalCtx, cancel := context.WithTimeout(r.Context(), 35*time.Second)
	defer cancel()

	item, reason, err := s.proactive.runEvaluation(evalCtx, req.Force)
	if err != nil {
		errorJSON(w, http.StatusBadGateway, "proactive_eval_failed")
		return
	}
	s.proactive.mu.Lock()
	st, _ := s.proactive.store.Get(r.Context())
	s.proactive.mu.Unlock()

	writeJSON(w, http.StatusOK, map[string]any{
		"sent":         item != nil,
		"reason":       reason,
		"notification": item,
		"state":        st,
	})
}
