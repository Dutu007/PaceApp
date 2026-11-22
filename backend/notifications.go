package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

var errNotificationNotFound = errors.New("notification_not_found")

type pushNotification struct {
	ID        int64     `json:"id"`
	Title     string    `json:"title"`
	Body      string    `json:"body"`
	Category  string    `json:"category"`
	SessionID string    `json:"session_id,omitempty"`
	Source    string    `json:"source"`
	IsRead    bool      `json:"is_read"`
	CreatedAt time.Time `json:"created_at"`
}

type createNotificationRequest struct {
	Title     string `json:"title"`
	Body      string `json:"body"`
	Category  string `json:"category"`
	SessionID string `json:"session_id"`
	Source    string `json:"source"`
}

type notificationSummary struct {
	UnreadCount int64 `json:"unread_count"`
	LatestID    int64 `json:"latest_id"`
}

type notificationStore interface {
	Create(ctx context.Context, req createNotificationRequest) (pushNotification, error)
	List(ctx context.Context, limit int, unreadOnly bool, afterID int64) ([]pushNotification, notificationSummary, error)
	ListAfter(ctx context.Context, afterID int64, limit int) ([]pushNotification, error)
	Summary(ctx context.Context) (notificationSummary, error)
	MarkRead(ctx context.Context, id int64) error
	MarkAllRead(ctx context.Context) error
}

type notificationHub struct {
	mu          sync.RWMutex
	nextID      uint64
	subscribers map[uint64]chan pushNotification
}

func newNotificationHub() *notificationHub {
	return &notificationHub{
		subscribers: make(map[uint64]chan pushNotification),
	}
}

func (h *notificationHub) subscribe() (<-chan pushNotification, func()) {
	ch := make(chan pushNotification, 32)
	h.mu.Lock()
	h.nextID++
	id := h.nextID
	h.subscribers[id] = ch
	h.mu.Unlock()

	cancel := func() {
		h.mu.Lock()
		if existing, ok := h.subscribers[id]; ok {
			delete(h.subscribers, id)
			close(existing)
		}
		h.mu.Unlock()
	}
	return ch, cancel
}

func (h *notificationHub) publish(item pushNotification) {
	if h == nil {
		return
	}
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, ch := range h.subscribers {
		select {
		case ch <- item:
		default:
		}
	}
}

type pgNotificationStore struct {
	pool *pgxpool.Pool
}

func (p *pgNotificationStore) Create(ctx context.Context, req createNotificationRequest) (pushNotification, error) {
	var item pushNotification
	err := p.pool.QueryRow(
		ctx,
		`insert into pace_notifications (title, body, category, session_id, source)
		 values ($1, $2, $3, $4, $5)
		 returning id, title, body, category, session_id, source, is_read, created_at`,
		req.Title, req.Body, req.Category, req.SessionID, req.Source,
	).Scan(
		&item.ID, &item.Title, &item.Body, &item.Category,
		&item.SessionID, &item.Source, &item.IsRead, &item.CreatedAt,
	)
	return item, err
}

func (p *pgNotificationStore) Summary(ctx context.Context) (notificationSummary, error) {
	var sum notificationSummary
	err := p.pool.QueryRow(
		ctx,
		`select count(*) filter (where not is_read), coalesce(max(id), 0) from pace_notifications`,
	).Scan(&sum.UnreadCount, &sum.LatestID)
	return sum, err
}

func (p *pgNotificationStore) List(ctx context.Context, limit int, unreadOnly bool, afterID int64) ([]pushNotification, notificationSummary, error) {
	sum, err := p.Summary(ctx)
	if err != nil {
		return nil, notificationSummary{}, err
	}
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	conds := []string{"1=1"}
	args := make([]any, 0, 2)
	if unreadOnly {
		conds = append(conds, "not is_read")
	}
	if afterID > 0 {
		args = append(args, afterID)
		conds = append(conds, fmt.Sprintf("id > $%d", len(args)))
	}
	args = append(args, limit)
	order := "order by id desc"
	if afterID > 0 {
		order = "order by id asc"
	}
	query := `select id, title, body, category, session_id, source, is_read, created_at
		from pace_notifications where ` + strings.Join(conds, " and ") + " " + order +
		fmt.Sprintf(" limit $%d", len(args))

	rows, err := p.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, notificationSummary{}, err
	}
	defer rows.Close()

	out := make([]pushNotification, 0)
	for rows.Next() {
		var item pushNotification
		if err := rows.Scan(
			&item.ID, &item.Title, &item.Body, &item.Category,
			&item.SessionID, &item.Source, &item.IsRead, &item.CreatedAt,
		); err != nil {
			return nil, notificationSummary{}, err
		}
		out = append(out, item)
	}
	return out, sum, rows.Err()
}

func (p *pgNotificationStore) ListAfter(ctx context.Context, afterID int64, limit int) ([]pushNotification, error) {
	items, _, err := p.List(ctx, limit, false, afterID)
	return items, err
}

func (p *pgNotificationStore) MarkRead(ctx context.Context, id int64) error {
	tag, err := p.pool.Exec(ctx, `update pace_notifications set is_read = true where id = $1`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return errNotificationNotFound
	}
	return nil
}

func (p *pgNotificationStore) MarkAllRead(ctx context.Context) error {
	_, err := p.pool.Exec(ctx, `update pace_notifications set is_read = true where not is_read`)
	return err
}

func (s *server) createAndBroadcastNotification(ctx context.Context, req createNotificationRequest) (pushNotification, error) {
	if s.notifications == nil {
		return pushNotification{}, errors.New("notifications_unavailable")
	}
	req.Title = strings.TrimSpace(req.Title)
	if req.Title == "" {
		req.Title = "小洛提醒"
	}
	req.Body = truncateRunes(strings.TrimSpace(req.Body), 8000)
	req.Category = strings.TrimSpace(req.Category)
	if req.Category == "" {
		req.Category = "reminder"
	}
	req.Source = strings.TrimSpace(req.Source)
	if req.Source == "" {
		req.Source = "hermes"
	}
	req.SessionID = strings.TrimSpace(req.SessionID)
	item, err := s.notifications.Create(ctx, req)
	if err != nil {
		return pushNotification{}, err
	}
	if s.notifHub != nil {
		s.notifHub.publish(item)
	}
	return item, nil
}

func (s *server) pushNotify(w http.ResponseWriter, r *http.Request) {
	if s.notifications == nil {
		errorJSON(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	var req createNotificationRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if strings.TrimSpace(req.Body) == "" {
		errorJSON(w, http.StatusBadRequest, "body_required")
		return
	}
	item, err := s.createAndBroadcastNotification(r.Context(), req)
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "push_failed")
		return
	}
	writeJSON(w, http.StatusCreated, item)
}

func (s *server) pushTest(w http.ResponseWriter, r *http.Request) {
	if s.notifications == nil {
		errorJSON(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	item, err := s.createAndBroadcastNotification(r.Context(), createNotificationRequest{
		Title:    "Pace 通知测试",
		Body:     "实时连接正常，小洛已可随时向你主动发送提醒。",
		Category: "system",
		Source:   "test",
	})
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "push_failed")
		return
	}
	writeJSON(w, http.StatusCreated, item)
}

func (s *server) listNotifications(w http.ResponseWriter, r *http.Request) {
	if s.notifications == nil {
		errorJSON(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	q := r.URL.Query()
	limit, _ := strconv.Atoi(strings.TrimSpace(q.Get("limit")))
	unreadRaw := strings.ToLower(strings.TrimSpace(q.Get("unread_only")))
	unreadOnly := unreadRaw == "1" || unreadRaw == "true"
	afterID, _ := strconv.ParseInt(strings.TrimSpace(q.Get("after_id")), 10, 64)

	items, sum, err := s.notifications.List(r.Context(), limit, unreadOnly, afterID)
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "query_failed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"items":        items,
		"unread_count": sum.UnreadCount,
		"latest_id":    sum.LatestID,
	})
}

func (s *server) markNotificationRead(w http.ResponseWriter, r *http.Request) {
	if s.notifications == nil {
		errorJSON(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		errorJSON(w, http.StatusBadRequest, "invalid_id")
		return
	}
	err = s.notifications.MarkRead(r.Context(), id)
	if errors.Is(err, errNotificationNotFound) {
		errorJSON(w, http.StatusNotFound, "not_found")
		return
	}
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "update_failed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (s *server) markAllNotificationsRead(w http.ResponseWriter, r *http.Request) {
	if s.notifications == nil {
		errorJSON(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	if err := s.notifications.MarkAllRead(r.Context()); err != nil {
		errorJSON(w, http.StatusInternalServerError, "update_failed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (s *server) eventsStream(w http.ResponseWriter, r *http.Request) {
	if s.notifications == nil || s.notifHub == nil {
		errorJSON(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	_ = http.NewResponseController(w).SetWriteDeadline(time.Time{})

	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no")

	// Subscribe before catch-up queries so no live event is lost across the boundary.
	ch, cancel := s.notifHub.subscribe()
	defer cancel()

	sum, err := s.notifications.Summary(r.Context())
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "stream_init_failed")
		return
	}

	w.WriteHeader(http.StatusOK)
	flusher, _ := w.(http.Flusher)

	connPayload, _ := json.Marshal(map[string]any{
		"status":       "ok",
		"latest_id":    sum.LatestID,
		"unread_count": sum.UnreadCount,
	})
	if _, err := fmt.Fprintf(w, "event: connected\ndata: %s\n\n", connPayload); err != nil {
		return
	}
	if flusher != nil {
		flusher.Flush()
	}

	afterID, _ := strconv.ParseInt(strings.TrimSpace(r.URL.Query().Get("after_id")), 10, 64)
	var lastSentID int64 = afterID
	if afterID > 0 {
		missed, err := s.notifications.ListAfter(r.Context(), afterID, 50)
		if err == nil {
			for _, item := range missed {
				if item.ID <= lastSentID {
					continue
				}
				raw, mErr := json.Marshal(item)
				if mErr != nil {
					continue
				}
				if _, wErr := fmt.Fprintf(w, "id: %d\nevent: notification\ndata: %s\n\n", item.ID, raw); wErr != nil {
					return
				}
				lastSentID = item.ID
			}
			if flusher != nil {
				flusher.Flush()
			}
		}
	}

	ticker := time.NewTicker(25 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-r.Context().Done():
			return
		case <-ticker.C:
			if _, wErr := fmt.Fprint(w, ": ping\n\n"); wErr != nil {
				return
			}
			if flusher != nil {
				flusher.Flush()
			}
		case item, ok := <-ch:
			if !ok {
				return
			}
			if item.ID <= lastSentID {
				continue
			}
			raw, mErr := json.Marshal(item)
			if mErr != nil {
				continue
			}
			if _, wErr := fmt.Fprintf(w, "id: %d\nevent: notification\ndata: %s\n\n", item.ID, raw); wErr != nil {
				return
			}
			lastSentID = item.ID
			if flusher != nil {
				flusher.Flush()
			}
		}
	}
}
