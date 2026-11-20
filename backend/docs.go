package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

type docSummary struct {
	Path      string `json:"path"`
	Category  string `json:"category"`
	Title     string `json:"title"`
	Preview   string `json:"preview"`
	MimeType  string `json:"mime_type"`
	Editable  bool   `json:"editable"`
	Size      int64  `json:"size"`
	UpdatedAt string `json:"updated_at"`
}

type docContentResponse struct {
	Path         string `json:"path"`
	Category     string `json:"category"`
	Title        string `json:"title"`
	Content      string `json:"content"`
	MimeType     string `json:"mime_type"`
	RawAvailable bool   `json:"raw_available"`
	Editable     bool   `json:"editable"`
	UpdatedAt    string `json:"updated_at"`
}

type saveDocRequest struct {
	Path    string `json:"path"`
	Content string `json:"content"`
}

type docStore struct {
	assistantDir string
	memoryFile   string
	tz           *time.Location
}

func newDocStore(assistantDir, memoryFile string) *docStore {
	loc, err := time.LoadLocation("Asia/Shanghai")
	if err != nil {
		loc = time.FixedZone("CST", 8*3600)
	}
	return &docStore{
		assistantDir: strings.TrimSpace(assistantDir),
		memoryFile:   strings.TrimSpace(memoryFile),
		tz:           loc,
	}
}

func (d *docStore) list() ([]docSummary, error) {
	items := make([]docSummary, 0)
	if d.memoryFile != "" {
		if info, err := os.Stat(d.memoryFile); err == nil && !info.IsDir() {
			raw, _ := os.ReadFile(d.memoryFile)
			items = append(items, docSummary{
				Path:      "memory/MEMORY.md",
				Category:  "memory",
				Title:     "Hermes 长期记忆",
				Preview:   summarizePreview(string(raw)),
				Editable:  false,
				Size:      info.Size(),
				UpdatedAt: info.ModTime().In(d.tz).Format(time.RFC3339),
			})
		}
	}
	if d.assistantDir == "" {
		return items, nil
	}
	err := filepath.WalkDir(d.assistantDir, func(full string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil || entry.IsDir() {
			return nil
		}
		rel, err := filepath.Rel(d.assistantDir, full)
		if err != nil {
			return nil
		}
		relSlash := filepath.ToSlash(rel)
		ext := strings.ToLower(filepath.Ext(entry.Name()))
		if !isSupportedDocExt(ext) {
			return nil
		}
		if strings.EqualFold(entry.Name(), "README.md") {
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			return nil
		}
		category := classifyDoc(relSlash)
		var raw []byte
		if isTextReadableExt(ext) && info.Size() <= 256<<10 {
			raw, _ = os.ReadFile(full)
		}
		title := strings.TrimSuffix(entry.Name(), filepath.Ext(entry.Name()))
		if relSlash == "images/running/notes.json" {
			title = "跑步打卡记录"
		} else if relSlash == "user_profile.json" {
			title = "用户画像"
		}
		items = append(items, docSummary{
			Path:      relSlash,
			Category:  category,
			Title:     title,
			Preview:   previewForDoc(ext, raw, info.Size()),
			MimeType:  mimeTypeForExt(ext),
			Editable:  ext == ".md" && (strings.HasPrefix(relSlash, "notes/") || strings.HasPrefix(relSlash, "diary/")),
			Size:      info.Size(),
			UpdatedAt: info.ModTime().In(d.tz).Format(time.RFC3339),
		})
		return nil
	})
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	sort.Slice(items, func(i, j int) bool {
		return items[i].UpdatedAt > items[j].UpdatedAt
	})
	return items, nil
}

func (d *docStore) read(relPath string) (docContentResponse, error) {
	clean, err := sanitizeRelPath(relPath)
	if err != nil {
		return docContentResponse{}, err
	}
	if clean == "memory/MEMORY.md" {
		if d.memoryFile == "" {
			return docContentResponse{}, os.ErrNotExist
		}
		info, err := os.Stat(d.memoryFile)
		if err != nil {
			return docContentResponse{}, err
		}
		raw, err := os.ReadFile(d.memoryFile)
		if err != nil {
			return docContentResponse{}, err
		}
		return docContentResponse{
			Path:         clean,
			Category:     "memory",
			Title:        "Hermes 长期记忆",
			Content:      string(raw),
			MimeType:     "text/markdown; charset=utf-8",
			RawAvailable: true,
			Editable:     false,
			UpdatedAt:    info.ModTime().In(d.tz).Format(time.RFC3339),
		}, nil
	}
	if d.assistantDir == "" {
		return docContentResponse{}, os.ErrNotExist
	}
	full, err := d.resolveAssistantFile(clean)
	if err != nil {
		return docContentResponse{}, err
	}
	info, err := os.Stat(full)
	if err != nil {
		return docContentResponse{}, err
	}
	ext := strings.ToLower(filepath.Ext(clean))
	var contentText string
	if isTextReadableExt(ext) {
		raw, err := os.ReadFile(full)
		if err != nil {
			return docContentResponse{}, err
		}
		contentText = string(raw)
	} else {
		contentText = extractDocText(full, ext, info.Size(), 20000)
	}
	return docContentResponse{
		Path:         clean,
		Category:     classifyDoc(clean),
		Title:        strings.TrimSuffix(filepath.Base(clean), filepath.Ext(clean)),
		Content:      contentText,
		MimeType:     mimeTypeForExt(ext),
		RawAvailable: true,
		Editable:     ext == ".md" && (strings.HasPrefix(clean, "notes/") || strings.HasPrefix(clean, "diary/")),
		UpdatedAt:    info.ModTime().In(d.tz).Format(time.RFC3339),
	}, nil
}

func (d *docStore) write(relPath, content string) (docContentResponse, error) {
	clean, err := sanitizeRelPath(relPath)
	if err != nil {
		return docContentResponse{}, err
	}
	if !strings.HasSuffix(strings.ToLower(clean), ".md") ||
		(!strings.HasPrefix(clean, "notes/") && !strings.HasPrefix(clean, "diary/")) {
		return docContentResponse{}, errors.New("doc_not_writable")
	}
	full, err := d.resolveAssistantFile(clean)
	if err != nil {
		return docContentResponse{}, err
	}
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		return docContentResponse{}, err
	}
	if err := os.WriteFile(full, []byte(content), 0o644); err != nil {
		return docContentResponse{}, err
	}
	return d.read(clean)
}

func (d *docStore) appendEntry(mode, customPath, title, content string, tags []string) (string, error) {
	if d.assistantDir == "" {
		return "", errors.New("assistant_dir_unset")
	}
	now := time.Now().In(d.tz)
	var targetRel string
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "diary":
		targetRel = fmt.Sprintf("diary/%04d/%02d/%02d.md", now.Year(), int(now.Month()), now.Day())
	case "note":
		if strings.TrimSpace(customPath) != "" {
			targetRel = customPath
		} else {
			targetRel = "notes/随手记.md"
		}
	default:
		if strings.TrimSpace(customPath) == "" {
			return "", nil
		}
		targetRel = customPath
	}

	clean, err := sanitizeRelPath(targetRel)
	if err != nil {
		return "", err
	}
	if !strings.HasSuffix(strings.ToLower(clean), ".md") ||
		(!strings.HasPrefix(clean, "notes/") && !strings.HasPrefix(clean, "diary/")) {
		return "", errors.New("doc_not_writable")
	}
	full, err := d.resolveAssistantFile(clean)
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		return "", err
	}

	existingBytes, err := os.ReadFile(full)
	existing := ""
	if err == nil {
		existing = string(existingBytes)
	}
	var b strings.Builder
	if strings.TrimSpace(existing) == "" {
		headerTitle := strings.TrimSuffix(filepath.Base(clean), ".md")
		if strings.HasPrefix(clean, "diary/") {
			headerTitle = now.Format("2006-01-02 日记")
		}
		b.WriteString("# ")
		b.WriteString(headerTitle)
		b.WriteString("\n\n")
	} else {
		b.WriteString(strings.TrimRight(existing, "\r\n"))
		b.WriteString("\n\n")
	}
	b.WriteString(fmt.Sprintf("- **%s**", now.Format("15:04")))
	if title != "" {
		b.WriteString(" [" + title + "]")
	}
	b.WriteString(" ")
	b.WriteString(content)
	if len(tags) > 0 {
		b.WriteString("  `#" + strings.Join(tags, " #") + "`")
	}
	b.WriteString("\n")
	if err := os.WriteFile(full, []byte(b.String()), 0o644); err != nil {
		return "", err
	}
	return clean, nil
}

func (d *docStore) resolveAssistantFile(cleanRel string) (string, error) {
	baseAbs, err := filepath.Abs(d.assistantDir)
	if err != nil {
		return "", err
	}
	target := filepath.Join(baseAbs, filepath.FromSlash(cleanRel))
	targetAbs, err := filepath.Abs(target)
	if err != nil {
		return "", err
	}
	if targetAbs != baseAbs && !strings.HasPrefix(targetAbs, baseAbs+string(filepath.Separator)) {
		return "", errors.New("invalid_path")
	}
	return targetAbs, nil
}

func sanitizeRelPath(raw string) (string, error) {
	trimmed := strings.TrimSpace(strings.ReplaceAll(raw, "\\", "/"))
	trimmed = strings.TrimPrefix(trimmed, "/")
	if trimmed == "" || strings.Contains(trimmed, "..") || strings.ContainsAny(trimmed, "\x00\r\n") {
		return "", errors.New("invalid_path")
	}
	clean := filepath.ToSlash(filepath.Clean(trimmed))
	if clean == "." || strings.HasPrefix(clean, "../") || strings.HasPrefix(clean, "/") {
		return "", errors.New("invalid_path")
	}
	return clean, nil
}

func classifyDoc(rel string) string {
	switch {
	case strings.HasPrefix(rel, "uploads/"):
		return "upload"
	case strings.HasPrefix(rel, "diary/"):
		return "diary"
	case strings.HasPrefix(rel, "notes/"):
		return "note"
	default:
		return "data"
	}
}

func summarizePreview(raw string) string {
	lines := strings.Split(raw, "\n")
	parts := make([]string, 0, 3)
	for _, line := range lines {
		t := strings.TrimSpace(strings.TrimLeft(line, "#>-* "))
		if t == "" || strings.HasPrefix(t, "---") {
			continue
		}
		parts = append(parts, t)
		if len(parts) >= 2 {
			break
		}
	}
	joined := strings.Join(parts, " ")
	runes := []rune(joined)
	if len(runes) > 90 {
		return string(runes[:90]) + "…"
	}
	return joined
}

func (s *server) listDocs(w http.ResponseWriter, r *http.Request) {
	if s.docs == nil {
		writeJSON(w, http.StatusOK, map[string]any{"items": []docSummary{}})
		return
	}
	items, err := s.docs.list()
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "docs_read_failed")
		return
	}
	if cat := strings.TrimSpace(r.URL.Query().Get("category")); cat != "" && cat != "all" {
		filtered := make([]docSummary, 0, len(items))
		for _, item := range items {
			if item.Category == cat {
				filtered = append(filtered, item)
			}
		}
		items = filtered
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

func (s *server) getDocContent(w http.ResponseWriter, r *http.Request) {
	if s.docs == nil {
		errorJSON(w, http.StatusNotFound, "doc_not_found")
		return
	}
	doc, err := s.docs.read(r.URL.Query().Get("path"))
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			errorJSON(w, http.StatusNotFound, "doc_not_found")
		} else {
			errorJSON(w, http.StatusBadRequest, "invalid_path")
		}
		return
	}
	writeJSON(w, http.StatusOK, doc)
}

func (s *server) putDocContent(w http.ResponseWriter, r *http.Request) {
	if s.docs == nil {
		errorJSON(w, http.StatusServiceUnavailable, "docs_unavailable")
		return
	}
	var req saveDocRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	doc, err := s.docs.write(req.Path, req.Content)
	if err != nil {
		errorJSON(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, doc)
}

func (s *server) uploadFile(w http.ResponseWriter, r *http.Request) {
	if s.docs == nil || s.docs.assistantDir == "" {
		errorJSON(w, http.StatusServiceUnavailable, "uploads_unavailable")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 12<<20)
	r.Body = http.MaxBytesReader(w, r.Body, 25<<20)
	if err := r.ParseMultipartForm(24 << 20); err != nil {
		errorJSON(w, http.StatusBadRequest, "invalid_upload")
		return
	}
	file, header, err := r.FormFile("file")
	if err != nil {
		errorJSON(w, http.StatusBadRequest, "missing_file")
		return
	}
	defer file.Close()

	baseName := filepath.Base(strings.ReplaceAll(header.Filename, "\\", "/"))
	safeName := sanitizeUploadFilename(baseName)
	stamp := time.Now().In(s.docs.tz).Format("20060102_150405")
	rel := fmt.Sprintf("uploads/%s_%s", stamp, safeName)
	full, err := s.docs.resolveAssistantFile(rel)
	if err != nil {
		errorJSON(w, http.StatusBadRequest, "invalid_path")
		return
	}
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		errorJSON(w, http.StatusInternalServerError, "upload_failed")
		return
	}
	out, err := os.OpenFile(full, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "upload_failed")
		return
	}
	written, copyErr := out.ReadFrom(file)
	closeErr := out.Close()
	if copyErr != nil || closeErr != nil {
		errorJSON(w, http.StatusInternalServerError, "upload_failed")
		return
	}
	ext := strings.ToLower(filepath.Ext(baseName))
	extracted := ""
	if !isImageExt(ext) {
		extracted = extractDocText(full, ext, written, 12000)
	}
	writeJSON(w, http.StatusCreated, map[string]any{
		"path":           rel,
		"host_path":      "/home/dutu/workspace/personal-assistant/data/" + rel,
		"name":           baseName,
		"size":           written,
		"mime_type":      mimeTypeForExt(ext),
		"extracted_text": extracted,
	})
}

func sanitizeUploadFilename(name string) string {
	var b strings.Builder
	for _, r := range name {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9',
			r == '.', r == '-', r == '_', r >= 0x4e00 && r <= 0x9fff:
			b.WriteRune(r)
		default:
			b.WriteByte('_')
		}
	}
	out := strings.Trim(b.String(), "._")
	if out == "" {
		return "file.bin"
	}
	return out
}

// Keep json import used in case structured JSON summaries are added later.
var _ = json.Marshal
