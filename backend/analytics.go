package main

import (
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	analyticsFileMu   sync.Mutex
	runningImageRegex = regexp.MustCompile(`^跑步_(\d{2})-(\d{2})\.(jpg|jpeg|png|webp)$`)
	distanceKmRegex   = regexp.MustCompile(`(\d+(?:\.\d+)?)\s*(?:km|KM|Km|公里)`)
	dateYMDPattern    = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)
)

const defaultLabNote = "实验室学习打卡记录。arrive/leave 为 'YYYY-MM-DD HH:MM'，leave 为 null 表示进行中。minutes 由离开时自动计算。"
const defaultSleepNote = "睡眠记录。date 为醒来日期 'YYYY-MM-DD'，duration_minutes 为总睡眠分钟数。"

type labFileJSON struct {
	Note     string           `json:"note"`
	Sessions []labSessionItem `json:"sessions"`
}

type labSessionItem struct {
	Date    string  `json:"date"`
	Arrive  string  `json:"arrive"`
	Leave   *string `json:"leave"`
	Minutes *int    `json:"minutes"`
	Note    string  `json:"note,omitempty"`
}

type sleepFileJSON struct {
	Note    string            `json:"note"`
	Records []sleepRecordItem `json:"records"`
}

type sleepRecordItem struct {
	Date            string `json:"date"`
	Bedtime         string `json:"bedtime,omitempty"`
	WakeTime        string `json:"wake_time,omitempty"`
	DurationMinutes int    `json:"duration_minutes"`
	DeepMinutes     *int   `json:"deep_minutes,omitempty"`
	LightMinutes    *int   `json:"light_minutes,omitempty"`
	RemMinutes      *int   `json:"rem_minutes,omitempty"`
	AwakeMinutes    *int   `json:"awake_minutes,omitempty"`
	Score           *int   `json:"score,omitempty"`
	Source          string `json:"source,omitempty"`
	Note            string `json:"note,omitempty"`
}

type runningFileJSON struct {
	Note    string              `json:"note"`
	Records []runningRecordItem `json:"records"`
}

type runningRecordItem struct {
	Date            string  `json:"date"`
	DistanceKm      float64 `json:"distance_km,omitempty"`
	DurationMinutes int     `json:"duration_minutes,omitempty"`
	Pace            string  `json:"pace,omitempty"`
	Source          string  `json:"source,omitempty"`
	Note            string  `json:"note,omitempty"`
	ImagePath       string  `json:"image_path,omitempty"`
}

type customMetricFileJSON struct {
	ID      string                   `json:"id"`
	Name    string                   `json:"name"`
	Unit    string                   `json:"unit"`
	Color   string                   `json:"color,omitempty"`
	Goal    float64                  `json:"goal,omitempty"`
	Records []customMetricRecordItem `json:"records"`
}

type customMetricRecordItem struct {
	Date  string  `json:"date"`
	Value float64 `json:"value"`
	Note  string  `json:"note,omitempty"`
}

type dailyMetricPoint struct {
	Date    string  `json:"date"`
	Label   string  `json:"label"`
	Weekday string  `json:"weekday"`
	Value   float64 `json:"value"`
	Count   int     `json:"count"`
}

type sleepDailyPoint struct {
	Date            string `json:"date"`
	Label           string `json:"label"`
	Weekday         string `json:"weekday"`
	DurationMinutes int    `json:"duration_minutes"`
	DeepMinutes     *int   `json:"deep_minutes,omitempty"`
	LightMinutes    *int   `json:"light_minutes,omitempty"`
	RemMinutes      *int   `json:"rem_minutes,omitempty"`
	Score           *int   `json:"score,omitempty"`
	Bedtime         string `json:"bedtime,omitempty"`
	WakeTime        string `json:"wake_time,omitempty"`
}

type labAnalyticsSummary struct {
	ActiveSession         *labSessionItem    `json:"active_session"`
	ActiveElapsedMinutes  int                `json:"active_elapsed_minutes"`
	TodayMinutes          int                `json:"today_minutes"`
	WeekMinutes           int                `json:"week_minutes"`
	MonthMinutes          int                `json:"month_minutes"`
	TotalMinutes          int                `json:"total_minutes"`
	SessionCount          int                `json:"session_count"`
	AvgDailyMinutes       int                `json:"avg_daily_minutes"`
	StreakDays            int                `json:"streak_days"`
	DailySeries           []dailyMetricPoint `json:"daily_series"`
	RecentSessions        []labSessionItem   `json:"recent_sessions"`
}

type sleepAnalyticsSummary struct {
	HasData               bool              `json:"has_data"`
	AvgDurationMinutes    int               `json:"avg_duration_minutes"`
	LatestDurationMinutes int               `json:"latest_duration_minutes"`
	AvgScore              *int              `json:"avg_score,omitempty"`
	RecordCount           int               `json:"record_count"`
	DailySeries           []sleepDailyPoint `json:"daily_series"`
	RecentRecords         []sleepRecordItem `json:"recent_records"`
}

type runningAnalyticsSummary struct {
	TotalRuns       int                 `json:"total_runs"`
	RunsInRange     int                 `json:"runs_in_range"`
	TotalDistanceKm float64             `json:"total_distance_km"`
	DailySeries     []dailyMetricPoint  `json:"daily_series"`
	RecentRuns      []runningRecordItem `json:"recent_runs"`
}

type customMetricSummary struct {
	ID          string             `json:"id"`
	Name        string             `json:"name"`
	Unit        string             `json:"unit"`
	Color       string             `json:"color"`
	Goal        float64            `json:"goal"`
	LatestValue float64            `json:"latest_value"`
	AvgValue    float64            `json:"avg_value"`
	DailySeries []dailyMetricPoint `json:"daily_series"`
}

type analyticsOverview struct {
	TodayLabMinutes    int  `json:"today_lab_minutes"`
	WeekLabMinutes     int  `json:"week_lab_minutes"`
	TotalLabMinutes    int  `json:"total_lab_minutes"`
	LabActive          bool `json:"lab_active"`
	AvgSleepMinutes    int  `json:"avg_sleep_minutes"`
	LatestSleepMinutes int  `json:"latest_sleep_minutes"`
	SleepHasData       bool `json:"sleep_has_data"`
	RunningCount30d    int  `json:"running_count_30d"`
	RunningTotalCount  int  `json:"running_total_count"`
	LabStreakDays      int  `json:"lab_streak_days"`
}

type analyticsDashboardResponse struct {
	GeneratedAt   string                  `json:"generated_at"`
	Range         string                  `json:"range"`
	Overview      analyticsOverview       `json:"overview"`
	Lab           labAnalyticsSummary     `json:"lab"`
	Sleep         sleepAnalyticsSummary   `json:"sleep"`
	Running       runningAnalyticsSummary `json:"running"`
	CustomMetrics []customMetricSummary   `json:"custom_metrics"`
}

func writeJSONAtomic(path string, v any) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	raw, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	raw = append(raw, '\n')
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func weekdayCN(w time.Weekday) string {
	switch w {
	case time.Monday:
		return "周一"
	case time.Tuesday:
		return "周二"
	case time.Wednesday:
		return "周三"
	case time.Thursday:
		return "周四"
	case time.Friday:
		return "周五"
	case time.Saturday:
		return "周六"
	default:
		return "周日"
	}
}

func parseClockInDate(dateStr, rawTime string, tz *time.Location) (time.Time, error) {
	trimmed := strings.TrimSpace(rawTime)
	if trimmed == "" {
		return time.Time{}, fmt.Errorf("empty_time")
	}
	if len(trimmed) == 5 && strings.Contains(trimmed, ":") {
		return time.ParseInLocation("2006-01-02 15:04", dateStr+" "+trimmed, tz)
	}
	if t, err := time.ParseInLocation("2006-01-02 15:04", trimmed, tz); err == nil {
		return t, nil
	}
	if t, err := time.ParseInLocation("2006-01-02 15:04:05", trimmed, tz); err == nil {
		return t, nil
	}
	return time.Time{}, fmt.Errorf("invalid_time_format")
}

func buildDateWindow(datesPresent []string, rangeMode string, now time.Time, tz *time.Location) []time.Time {
	today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, tz)
	var start time.Time
	switch rangeMode {
	case "30d":
		start = today.AddDate(0, 0, -29)
	case "all":
		earliest := today.AddDate(0, 0, -6)
		hasAny := false
		for _, ds := range datesPresent {
			if t, err := time.ParseInLocation("2006-01-02", ds, tz); err == nil {
				if !hasAny || t.Before(earliest) {
					earliest = t
					hasAny = true
				}
			}
		}
		maxStart := today.AddDate(0, 0, -89)
		if earliest.Before(maxStart) {
			earliest = maxStart
		}
		start = earliest
	default:
		start = today.AddDate(0, 0, -6)
	}
	days := make([]time.Time, 0, 32)
	for cur := start; !cur.After(today); cur = cur.AddDate(0, 0, 1) {
		days = append(days, cur)
	}
	return days
}

func computeStreakDays(dayTotals map[string]int, now time.Time) int {
	todayKey := now.Format("2006-01-02")
	cur := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, now.Location())
	if dayTotals[todayKey] <= 0 {
		yesterday := cur.AddDate(0, 0, -1)
		if dayTotals[yesterday.Format("2006-01-02")] <= 0 {
			return 0
		}
		cur = yesterday
	}
	streak := 0
	for {
		if dayTotals[cur.Format("2006-01-02")] <= 0 {
			break
		}
		streak++
		cur = cur.AddDate(0, 0, -1)
	}
	return streak
}

func (d *docStore) loadLabLocked() (labFileJSON, error) {
	if d.assistantDir == "" {
		return labFileJSON{Note: defaultLabNote}, nil
	}
	full := filepath.Join(d.assistantDir, "lab", "sessions.json")
	raw, err := os.ReadFile(full)
	if err != nil {
		if os.IsNotExist(err) {
			return labFileJSON{Note: defaultLabNote, Sessions: []labSessionItem{}}, nil
		}
		return labFileJSON{}, err
	}
	var parsed labFileJSON
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return labFileJSON{}, err
	}
	if parsed.Note == "" {
		parsed.Note = defaultLabNote
	}
	if parsed.Sessions == nil {
		parsed.Sessions = []labSessionItem{}
	}
	return parsed, nil
}

func (d *docStore) loadSleepLocked() (sleepFileJSON, error) {
	if d.assistantDir == "" {
		return sleepFileJSON{Note: defaultSleepNote}, nil
	}
	full := filepath.Join(d.assistantDir, "sleep", "records.json")
	raw, err := os.ReadFile(full)
	if err != nil {
		if os.IsNotExist(err) {
			return sleepFileJSON{Note: defaultSleepNote, Records: []sleepRecordItem{}}, nil
		}
		return sleepFileJSON{}, err
	}
	var parsed sleepFileJSON
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return sleepFileJSON{}, err
	}
	if parsed.Note == "" {
		parsed.Note = defaultSleepNote
	}
	if parsed.Records == nil {
		parsed.Records = []sleepRecordItem{}
	}
	return parsed, nil
}

func (d *docStore) loadRunningRecords(now time.Time) []runningRecordItem {
	if d.assistantDir == "" {
		return nil
	}
	byDate := map[string]runningRecordItem{}

	// 1. Scan legacy images/running/notes.json ("MM-DD": ["..."])
	notesMap := map[string][]string{}
	notesPath := filepath.Join(d.assistantDir, "images", "running", "notes.json")
	if rawNotes, err := os.ReadFile(notesPath); err == nil {
		_ = json.Unmarshal(rawNotes, &notesMap)
	}

	// 2. Scan running proof images images/running/跑步_MM-DD.(jpg|png|webp)
	runDir := filepath.Join(d.assistantDir, "images", "running")
	if entries, err := os.ReadDir(runDir); err == nil {
		for _, ent := range entries {
			if ent.IsDir() {
				continue
			}
			m := runningImageRegex.FindStringSubmatch(ent.Name())
			if m == nil {
				continue
			}
			mmdd := m[1] + "-" + m[2]
			year := now.Year()
			if info, err := ent.Info(); err == nil {
				mod := info.ModTime().In(d.tz)
				year = mod.Year()
			}
			fullDate := fmt.Sprintf("%04d-%s", year, mmdd)
			noteText := strings.Join(notesMap[mmdd], "；")
			dist := extractDistanceKm(noteText)
			byDate[fullDate] = runningRecordItem{
				Date:       fullDate,
				DistanceKm: dist,
				Source:     "image",
				Note:       noteText,
				ImagePath:  "images/running/" + ent.Name(),
			}
		}
	}

	// Merge any notes.json entries that lack a corresponding image
	for mmdd, list := range notesMap {
		if len(mmdd) != 5 || mmdd[2] != '-' {
			continue
		}
		monthInt, err := strconv.Atoi(mmdd[:2])
		if err != nil {
			continue
		}
		year := now.Year()
		if monthInt > int(now.Month())+1 {
			year--
		}
		fullDate := fmt.Sprintf("%04d-%s", year, mmdd)
		if _, exists := byDate[fullDate]; !exists {
			noteText := strings.Join(list, "；")
			byDate[fullDate] = runningRecordItem{
				Date:       fullDate,
				DistanceKm: extractDistanceKm(noteText),
				Source:     "notes",
				Note:       noteText,
			}
		}
	}

	// 3. Merge structured running/records.json (takes precedence while keeping image_path)
	structPath := filepath.Join(d.assistantDir, "running", "records.json")
	if rawStruct, err := os.ReadFile(structPath); err == nil {
		var parsed runningFileJSON
		if json.Unmarshal(rawStruct, &parsed) == nil {
			for _, rec := range parsed.Records {
				dStr := strings.TrimSpace(rec.Date)
				if !dateYMDPattern.MatchString(dStr) {
					continue
				}
				if existing, ok := byDate[dStr]; ok && rec.ImagePath == "" {
					rec.ImagePath = existing.ImagePath
				}
				if rec.DistanceKm <= 0 && rec.Note != "" {
					rec.DistanceKm = extractDistanceKm(rec.Note)
				}
				if rec.Source == "" {
					rec.Source = "manual"
				}
				byDate[dStr] = rec
			}
		}
	}

	out := make([]runningRecordItem, 0, len(byDate))
	for _, r := range byDate {
		out = append(out, r)
	}
	sort.Slice(out, func(i, j int) bool {
		return out[i].Date > out[j].Date
	})
	return out
}

func extractDistanceKm(note string) float64 {
	m := distanceKmRegex.FindStringSubmatch(note)
	if m == nil {
		return 0
	}
	v, err := strconv.ParseFloat(m[1], 64)
	if err != nil {
		return 0
	}
	return math.Round(v*100) / 100
}

func (d *docStore) loadCustomMetrics(days []time.Time) []customMetricSummary {
	if d.assistantDir == "" {
		return []customMetricSummary{}
	}
	dir := filepath.Join(d.assistantDir, "metrics")
	entries, err := os.ReadDir(dir)
	if err != nil {
		return []customMetricSummary{}
	}
	out := make([]customMetricSummary, 0, len(entries))
	for _, ent := range entries {
		if ent.IsDir() || !strings.HasSuffix(strings.ToLower(ent.Name()), ".json") {
			continue
		}
		raw, err := os.ReadFile(filepath.Join(dir, ent.Name()))
		if err != nil {
			continue
		}
		var parsed customMetricFileJSON
		if err := json.Unmarshal(raw, &parsed); err != nil {
			continue
		}
		id := strings.TrimSpace(parsed.ID)
		if id == "" {
			id = strings.TrimSuffix(ent.Name(), filepath.Ext(ent.Name()))
		}
		name := strings.TrimSpace(parsed.Name)
		if name == "" {
			name = id
		}
		color := strings.TrimSpace(parsed.Color)
		if color == "" {
			color = "#0E5A6F"
		}
		daySums := map[string]float64{}
		dayCounts := map[string]int{}
		var latestDate string
		var latestVal float64
		var totalSum float64
		for _, rec := range parsed.Records {
			if !dateYMDPattern.MatchString(rec.Date) {
				continue
			}
			daySums[rec.Date] += rec.Value
			dayCounts[rec.Date]++
			totalSum += rec.Value
			if rec.Date >= latestDate {
				latestDate = rec.Date
				latestVal = daySums[rec.Date]
			}
		}
		avgVal := 0.0
		if len(daySums) > 0 {
			avgVal = math.Round((totalSum/float64(len(daySums)))*100) / 100
		}
		series := make([]dailyMetricPoint, 0, len(days))
		for _, day := range days {
			key := day.Format("2006-01-02")
			series = append(series, dailyMetricPoint{
				Date:    key,
				Label:   day.Format("01-02"),
				Weekday: weekdayCN(day.Weekday()),
				Value:   daySums[key],
				Count:   dayCounts[key],
			})
		}
		out = append(out, customMetricSummary{
			ID:          id,
			Name:        name,
			Unit:        parsed.Unit,
			Color:       color,
			Goal:        parsed.Goal,
			LatestValue: latestVal,
			AvgValue:    avgVal,
			DailySeries: series,
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out
}

func (s *server) getAnalytics(w http.ResponseWriter, r *http.Request) {
	rangeMode := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("range")))
	switch rangeMode {
	case "30d", "all":
	default:
		rangeMode = "7d"
	}

	now := time.Now().In(s.docs.tz)
	todayKey := now.Format("2006-01-02")
	monthPrefix := now.Format("2006-01")
	isoOffset := (int(now.Weekday()) + 6) % 7 // Monday = 0
	weekStart := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, s.docs.tz).AddDate(0, 0, -isoOffset)
	weekStartKey := weekStart.Format("2006-01-02")
	cutoff30dKey := now.AddDate(0, 0, -29).Format("2006-01-02")

	analyticsFileMu.Lock()
	labFile, labErr := s.docs.loadLabLocked()
	sleepFile, sleepErr := s.docs.loadSleepLocked()
	analyticsFileMu.Unlock()

	if labErr != nil || sleepErr != nil {
		errorJSON(w, http.StatusInternalServerError, "analytics_read_failed")
		return
	}
	runs := s.docs.loadRunningRecords(now)

	// Collect all dates to anchor "all" range cleanly
	allDates := make([]string, 0, len(labFile.Sessions)+len(sleepFile.Records)+len(runs))
	for _, ls := range labFile.Sessions {
		if dateYMDPattern.MatchString(ls.Date) {
			allDates = append(allDates, ls.Date)
		}
	}
	for _, sr := range sleepFile.Records {
		if dateYMDPattern.MatchString(sr.Date) {
			allDates = append(allDates, sr.Date)
		}
	}
	for _, rr := range runs {
		if dateYMDPattern.MatchString(rr.Date) {
			allDates = append(allDates, rr.Date)
		}
	}
	days := buildDateWindow(allDates, rangeMode, now, s.docs.tz)

	// Aggregate Lab sessions
	var activeSess *labSessionItem
	activeElapsed := 0
	labDayMinutes := map[string]int{}
	labDayCount := map[string]int{}
	todayMin := 0
	weekMin := 0
	monthMin := 0
	totalMin := 0
	completedSessions := make([]labSessionItem, 0, len(labFile.Sessions))

	for _, rawSess := range labFile.Sessions {
		sess := rawSess
		if sess.Date == "" && len(sess.Arrive) >= 10 {
			sess.Date = sess.Arrive[:10]
		}
		arriveTime, arrErr := parseClockInDate(sess.Date, sess.Arrive, s.docs.tz)
		if sess.Leave == nil || strings.TrimSpace(*sess.Leave) == "" {
			if arrErr == nil {
				elapsed := int(math.Round(now.Sub(arriveTime).Minutes()))
				if elapsed < 0 {
					elapsed = 0
				}
				activeElapsed = elapsed
			}
			copySess := sess
			activeSess = &copySess
			continue
		}
		mins := 0
		if sess.Minutes != nil {
			mins = *sess.Minutes
		} else if arrErr == nil {
			if leaveTime, err := parseClockInDate(sess.Date, *sess.Leave, s.docs.tz); err == nil {
				if leaveTime.Before(arriveTime) {
					leaveTime = leaveTime.Add(24 * time.Hour)
				}
				mins = int(math.Round(leaveTime.Sub(arriveTime).Minutes()))
				if mins < 0 {
					mins = 0
				}
				sess.Minutes = &mins
			}
		}
		labDayMinutes[sess.Date] += mins
		labDayCount[sess.Date]++
		totalMin += mins
		if sess.Date == todayKey {
			todayMin += mins
		}
		if sess.Date >= weekStartKey {
			weekMin += mins
		}
		if strings.HasPrefix(sess.Date, monthPrefix) {
			monthMin += mins
		}
		completedSessions = append(completedSessions, sess)
	}

	sort.Slice(completedSessions, func(i, j int) bool {
		return completedSessions[i].Arrive > completedSessions[j].Arrive
	})
	if len(completedSessions) > 30 {
		completedSessions = completedSessions[:30]
	}

	activeDayCount := 0
	for _, m := range labDayMinutes {
		if m > 0 {
			activeDayCount++
		}
	}
	avgLabDaily := 0
	if activeDayCount > 0 {
		avgLabDaily = totalMin / activeDayCount
	}
	streak := computeStreakDays(labDayMinutes, now)

	labSeries := make([]dailyMetricPoint, 0, len(days))
	for _, day := range days {
		k := day.Format("2006-01-02")
		labSeries = append(labSeries, dailyMetricPoint{
			Date:    k,
			Label:   day.Format("01-02"),
			Weekday: weekdayCN(day.Weekday()),
			Value:   float64(labDayMinutes[k]),
			Count:   labDayCount[k],
		})
	}

	// Aggregate Sleep records
	sleepByDate := map[string]sleepRecordItem{}
	totalSleepMin := 0
	scoreSum := 0
	scoreCount := 0
	var latestSleep *sleepRecordItem
	sortedSleep := make([]sleepRecordItem, 0, len(sleepFile.Records))

	for _, rawRec := range sleepFile.Records {
		rec := rawRec
		if rec.DurationMinutes <= 0 && rec.Bedtime != "" && rec.WakeTime != "" {
			if bt, err := parseClockInDate(rec.Date, rec.Bedtime, s.docs.tz); err == nil {
				if wt, err2 := parseClockInDate(rec.Date, rec.WakeTime, s.docs.tz); err2 == nil {
					if wt.Before(bt) {
						bt = bt.Add(-24 * time.Hour)
					}
					rec.DurationMinutes = int(math.Round(wt.Sub(bt).Minutes()))
				}
			}
		}
		if rec.DurationMinutes < 0 {
			rec.DurationMinutes = 0
		}
		sleepByDate[rec.Date] = rec
		sortedSleep = append(sortedSleep, rec)
		totalSleepMin += rec.DurationMinutes
		if rec.Score != nil {
			scoreSum += *rec.Score
			scoreCount++
		}
		if latestSleep == nil || rec.Date >= latestSleep.Date {
			copyRec := rec
			latestSleep = &copyRec
		}
	}
	sort.Slice(sortedSleep, func(i, j int) bool {
		return sortedSleep[i].Date > sortedSleep[j].Date
	})
	if len(sortedSleep) > 20 {
		sortedSleep = sortedSleep[:20]
	}
	avgSleep := 0
	if len(sleepFile.Records) > 0 {
		avgSleep = totalSleepMin / len(sleepFile.Records)
	}
	var avgScorePtr *int
	if scoreCount > 0 {
		v := scoreSum / scoreCount
		avgScorePtr = &v
	}
	latestSleepMin := 0
	if latestSleep != nil {
		latestSleepMin = latestSleep.DurationMinutes
	}
	sleepSeries := make([]sleepDailyPoint, 0, len(days))
	for _, day := range days {
		k := day.Format("2006-01-02")
		pt := sleepDailyPoint{
			Date:    k,
			Label:   day.Format("01-02"),
			Weekday: weekdayCN(day.Weekday()),
		}
		if rec, ok := sleepByDate[k]; ok {
			pt.DurationMinutes = rec.DurationMinutes
			pt.DeepMinutes = rec.DeepMinutes
			pt.LightMinutes = rec.LightMinutes
			pt.RemMinutes = rec.RemMinutes
			pt.Score = rec.Score
			pt.Bedtime = rec.Bedtime
			pt.WakeTime = rec.WakeTime
		}
		sleepSeries = append(sleepSeries, pt)
	}

	// Aggregate Running records
	runByDate := map[string]runningRecordItem{}
	totalDist := 0.0
	runs30d := 0
	for _, rRec := range runs {
		runByDate[rRec.Date] = rRec
		totalDist += rRec.DistanceKm
		if rRec.Date >= cutoff30dKey {
			runs30d++
		}
	}
	totalDist = math.Round(totalDist*100) / 100
	runSeries := make([]dailyMetricPoint, 0, len(days))
	runsInRange := 0
	for _, day := range days {
		k := day.Format("2006-01-02")
		val := 0.0
		cnt := 0
		if rr, ok := runByDate[k]; ok {
			cnt = 1
			runsInRange++
			if rr.DistanceKm > 0 {
				val = rr.DistanceKm
			} else {
				val = 1
			}
		}
		runSeries = append(runSeries, dailyMetricPoint{
			Date:    k,
			Label:   day.Format("01-02"),
			Weekday: weekdayCN(day.Weekday()),
			Value:   val,
			Count:   cnt,
		})
	}
	recentRuns := runs
	if len(recentRuns) > 20 {
		recentRuns = recentRuns[:20]
	} else if recentRuns == nil {
		recentRuns = []runningRecordItem{}
	}

	customMetrics := s.docs.loadCustomMetrics(days)

	todayPlusActive := todayMin
	weekPlusActive := weekMin
	if activeSess != nil && activeSess.Date == todayKey {
		todayPlusActive += activeElapsed
		weekPlusActive += activeElapsed
	}

	resp := analyticsDashboardResponse{
		GeneratedAt: now.Format(time.RFC3339),
		Range:       rangeMode,
		Overview: analyticsOverview{
			TodayLabMinutes:    todayPlusActive,
			WeekLabMinutes:     weekPlusActive,
			TotalLabMinutes:    totalMin,
			LabActive:          activeSess != nil,
			AvgSleepMinutes:    avgSleep,
			LatestSleepMinutes: latestSleepMin,
			SleepHasData:       len(sleepFile.Records) > 0,
			RunningCount30d:    runs30d,
			RunningTotalCount:  len(runs),
			LabStreakDays:      streak,
		},
		Lab: labAnalyticsSummary{
			ActiveSession:        activeSess,
			ActiveElapsedMinutes: activeElapsed,
			TodayMinutes:         todayMin,
			WeekMinutes:          weekMin,
			MonthMinutes:         monthMin,
			TotalMinutes:         totalMin,
			SessionCount:         len(completedSessions),
			AvgDailyMinutes:      avgLabDaily,
			StreakDays:           streak,
			DailySeries:          labSeries,
			RecentSessions:       completedSessions,
		},
		Sleep: sleepAnalyticsSummary{
			HasData:               len(sleepFile.Records) > 0,
			AvgDurationMinutes:    avgSleep,
			LatestDurationMinutes: latestSleepMin,
			AvgScore:              avgScorePtr,
			RecordCount:           len(sleepFile.Records),
			DailySeries:           sleepSeries,
			RecentRecords:         sortedSleep,
		},
		Running: runningAnalyticsSummary{
			TotalRuns:       len(runs),
			RunsInRange:     runsInRange,
			TotalDistanceKm: totalDist,
			DailySeries:     runSeries,
			RecentRuns:      recentRuns,
		},
		CustomMetrics: customMetrics,
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *server) labCheckIn(w http.ResponseWriter, r *http.Request) {
	if s.docs.assistantDir == "" {
		errorJSON(w, http.StatusServiceUnavailable, "assistant_dir_unset")
		return
	}
	var req struct {
		Note string `json:"note"`
	}
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&req)

	now := time.Now().In(s.docs.tz)

	analyticsFileMu.Lock()
	defer analyticsFileMu.Unlock()

	labFile, err := s.docs.loadLabLocked()
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "lab_read_failed")
		return
	}
	for _, item := range labFile.Sessions {
		if item.Leave == nil || strings.TrimSpace(*item.Leave) == "" {
			errorJSON(w, http.StatusConflict, "lab_already_active")
			return
		}
	}

	item := labSessionItem{
		Date:    now.Format("2006-01-02"),
		Arrive:  now.Format("2006-01-02 15:04"),
		Leave:   nil,
		Minutes: nil,
		Note:    strings.TrimSpace(req.Note),
	}
	labFile.Sessions = append(labFile.Sessions, item)

	full := filepath.Join(s.docs.assistantDir, "lab", "sessions.json")
	if err := writeJSONAtomic(full, labFile); err != nil {
		errorJSON(w, http.StatusInternalServerError, "lab_write_failed")
		return
	}
	writeJSON(w, http.StatusCreated, item)
}

func (s *server) labCheckOut(w http.ResponseWriter, r *http.Request) {
	if s.docs.assistantDir == "" {
		errorJSON(w, http.StatusServiceUnavailable, "assistant_dir_unset")
		return
	}
	var req struct {
		Note string `json:"note"`
	}
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&req)

	now := time.Now().In(s.docs.tz)

	analyticsFileMu.Lock()
	defer analyticsFileMu.Unlock()

	labFile, err := s.docs.loadLabLocked()
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "lab_read_failed")
		return
	}
	idx := -1
	for i := len(labFile.Sessions) - 1; i >= 0; i-- {
		if labFile.Sessions[i].Leave == nil || strings.TrimSpace(*labFile.Sessions[i].Leave) == "" {
			idx = i
			break
		}
	}
	if idx < 0 {
		errorJSON(w, http.StatusNotFound, "no_active_lab_session")
		return
	}

	item := labFile.Sessions[idx]
	arriveTime, err := parseClockInDate(item.Date, item.Arrive, s.docs.tz)
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "invalid_arrive_time")
		return
	}
	leaveStr := now.Format("2006-01-02 15:04")
	mins := int(math.Round(now.Sub(arriveTime).Minutes()))
	if mins < 1 {
		mins = 1
	}
	item.Leave = &leaveStr
	item.Minutes = &mins
	if n := strings.TrimSpace(req.Note); n != "" {
		item.Note = n
	}
	labFile.Sessions[idx] = item

	full := filepath.Join(s.docs.assistantDir, "lab", "sessions.json")
	if err := writeJSONAtomic(full, labFile); err != nil {
		errorJSON(w, http.StatusInternalServerError, "lab_write_failed")
		return
	}
	writeJSON(w, http.StatusOK, item)
}

func (s *server) addLabSession(w http.ResponseWriter, r *http.Request) {
	if s.docs.assistantDir == "" {
		errorJSON(w, http.StatusServiceUnavailable, "assistant_dir_unset")
		return
	}
	var req struct {
		Date   string `json:"date"`
		Arrive string `json:"arrive"`
		Leave  string `json:"leave"`
		Note   string `json:"note"`
	}
	if !decodeJSON(w, r, &req) {
		return
	}
	dateStr := strings.TrimSpace(req.Date)
	if dateStr == "" {
		dateStr = time.Now().In(s.docs.tz).Format("2006-01-02")
	}
	if !dateYMDPattern.MatchString(dateStr) {
		errorJSON(w, http.StatusBadRequest, "invalid_date")
		return
	}
	arriveTime, err := parseClockInDate(dateStr, req.Arrive, s.docs.tz)
	if err != nil {
		errorJSON(w, http.StatusBadRequest, "invalid_arrive")
		return
	}
	leaveTime, err := parseClockInDate(dateStr, req.Leave, s.docs.tz)
	if err != nil {
		errorJSON(w, http.StatusBadRequest, "invalid_leave")
		return
	}
	if leaveTime.Before(arriveTime) && len(strings.TrimSpace(req.Leave)) <= 5 {
		leaveTime = leaveTime.Add(24 * time.Hour)
	}
	if !leaveTime.After(arriveTime) {
		errorJSON(w, http.StatusBadRequest, "leave_must_be_after_arrive")
		return
	}
	mins := int(math.Round(leaveTime.Sub(arriveTime).Minutes()))
	if mins < 1 {
		mins = 1
	}
	leaveStr := leaveTime.Format("2006-01-02 15:04")
	item := labSessionItem{
		Date:    dateStr,
		Arrive:  arriveTime.Format("2006-01-02 15:04"),
		Leave:   &leaveStr,
		Minutes: &mins,
		Note:    strings.TrimSpace(req.Note),
	}

	analyticsFileMu.Lock()
	defer analyticsFileMu.Unlock()

	labFile, err := s.docs.loadLabLocked()
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "lab_read_failed")
		return
	}
	labFile.Sessions = append(labFile.Sessions, item)
	sort.SliceStable(labFile.Sessions, func(i, j int) bool {
		return labFile.Sessions[i].Arrive < labFile.Sessions[j].Arrive
	})

	full := filepath.Join(s.docs.assistantDir, "lab", "sessions.json")
	if err := writeJSONAtomic(full, labFile); err != nil {
		errorJSON(w, http.StatusInternalServerError, "lab_write_failed")
		return
	}
	writeJSON(w, http.StatusCreated, item)
}

func (s *server) deleteLabSession(w http.ResponseWriter, r *http.Request) {
	if s.docs.assistantDir == "" {
		errorJSON(w, http.StatusServiceUnavailable, "assistant_dir_unset")
		return
	}
	arrive := strings.TrimSpace(r.URL.Query().Get("arrive"))
	if arrive == "" {
		errorJSON(w, http.StatusBadRequest, "arrive_required")
		return
	}

	analyticsFileMu.Lock()
	defer analyticsFileMu.Unlock()

	labFile, err := s.docs.loadLabLocked()
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "lab_read_failed")
		return
	}
	next := make([]labSessionItem, 0, len(labFile.Sessions))
	removed := false
	for _, it := range labFile.Sessions {
		if !removed && it.Arrive == arrive {
			removed = true
			continue
		}
		next = append(next, it)
	}
	if !removed {
		errorJSON(w, http.StatusNotFound, "session_not_found")
		return
	}
	labFile.Sessions = next

	full := filepath.Join(s.docs.assistantDir, "lab", "sessions.json")
	if err := writeJSONAtomic(full, labFile); err != nil {
		errorJSON(w, http.StatusInternalServerError, "lab_write_failed")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) upsertSleepRecord(w http.ResponseWriter, r *http.Request) {
	if s.docs.assistantDir == "" {
		errorJSON(w, http.StatusServiceUnavailable, "assistant_dir_unset")
		return
	}
	var req sleepRecordItem
	if !decodeJSON(w, r, &req) {
		return
	}
	req.Date = strings.TrimSpace(req.Date)
	if !dateYMDPattern.MatchString(req.Date) {
		errorJSON(w, http.StatusBadRequest, "invalid_date")
		return
	}
	req.Bedtime = strings.TrimSpace(req.Bedtime)
	req.WakeTime = strings.TrimSpace(req.WakeTime)
	req.Source = strings.TrimSpace(req.Source)
	if req.Source == "" {
		req.Source = "manual"
	}
	req.Note = strings.TrimSpace(req.Note)

	if req.DurationMinutes <= 0 {
		if req.Bedtime == "" || req.WakeTime == "" {
			errorJSON(w, http.StatusBadRequest, "duration_or_times_required")
			return
		}
		bt, err1 := parseClockInDate(req.Date, req.Bedtime, s.docs.tz)
		wt, err2 := parseClockInDate(req.Date, req.WakeTime, s.docs.tz)
		if err1 != nil || err2 != nil {
			errorJSON(w, http.StatusBadRequest, "invalid_sleep_times")
			return
		}
		if !wt.After(bt) {
			bt = bt.Add(-24 * time.Hour)
		}
		req.DurationMinutes = int(math.Round(wt.Sub(bt).Minutes()))
		req.Bedtime = bt.Format("2006-01-02 15:04")
		req.WakeTime = wt.Format("2006-01-02 15:04")
	}
	if req.DurationMinutes <= 0 {
		errorJSON(w, http.StatusBadRequest, "invalid_sleep_duration")
		return
	}

	analyticsFileMu.Lock()
	defer analyticsFileMu.Unlock()

	sleepFile, err := s.docs.loadSleepLocked()
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "sleep_read_failed")
		return
	}
	replaced := false
	for i := range sleepFile.Records {
		if sleepFile.Records[i].Date == req.Date {
			sleepFile.Records[i] = req
			replaced = true
			break
		}
	}
	if !replaced {
		sleepFile.Records = append(sleepFile.Records, req)
	}
	sort.Slice(sleepFile.Records, func(i, j int) bool {
		return sleepFile.Records[i].Date < sleepFile.Records[j].Date
	})

	full := filepath.Join(s.docs.assistantDir, "sleep", "records.json")
	if err := writeJSONAtomic(full, sleepFile); err != nil {
		errorJSON(w, http.StatusInternalServerError, "sleep_write_failed")
		return
	}
	writeJSON(w, http.StatusCreated, req)
}
