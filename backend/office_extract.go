package main

import (
	"archive/zip"
	"bytes"
	"encoding/xml"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

var officeMimeTypes = map[string]string{
	".md":   "text/markdown; charset=utf-8",
	".txt":  "text/plain; charset=utf-8",
	".csv":  "text/csv; charset=utf-8",
	".json": "application/json; charset=utf-8",
	".pdf":  "application/pdf",
	".doc":  "application/msword",
	".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
	".xls":  "application/vnd.ms-excel",
	".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
	".ppt":  "application/vnd.ms-powerpoint",
	".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
	".png":  "image/png",
	".jpg":  "image/jpeg",
	".jpeg": "image/jpeg",
	".webp": "image/webp",
}

func isSupportedDocExt(ext string) bool {
	_, ok := officeMimeTypes[strings.ToLower(ext)]
	return ok
}

func mimeTypeForExt(ext string) string {
	if mime, ok := officeMimeTypes[strings.ToLower(ext)]; ok {
		return mime
	}
	return "application/octet-stream"
}

func isTextReadableExt(ext string) bool {
	switch strings.ToLower(ext) {
	case ".md", ".txt", ".csv", ".json":
		return true
	default:
		return false
	}
}

func isImageExt(ext string) bool {
	switch strings.ToLower(ext) {
	case ".png", ".jpg", ".jpeg", ".webp":
		return true
	default:
		return false
	}
}

func formatBytesHuman(size int64) string {
	switch {
	case size >= 1<<20:
		return fmt.Sprintf("%.1f MB", float64(size)/(1<<20))
	case size >= 1<<10:
		return fmt.Sprintf("%.1f KB", float64(size)/(1<<10))
	default:
		return fmt.Sprintf("%d B", size)
	}
}

func previewForDoc(ext string, rawSample []byte, size int64) string {
	ext = strings.ToLower(ext)
	sizeStr := formatBytesHuman(size)
	switch {
	case ext == ".pdf":
		return fmt.Sprintf("PDF 文档 · %s", sizeStr)
	case ext == ".docx" || ext == ".doc":
		return fmt.Sprintf("Word 文档 (%s) · %s", strings.TrimPrefix(ext, "."), sizeStr)
	case ext == ".xlsx" || ext == ".xls":
		return fmt.Sprintf("Excel 表格 (%s) · %s", strings.TrimPrefix(ext, "."), sizeStr)
	case ext == ".pptx" || ext == ".ppt":
		return fmt.Sprintf("PPT 演示文稿 (%s) · %s", strings.TrimPrefix(ext, "."), sizeStr)
	case isImageExt(ext):
		return fmt.Sprintf("图片文件 (%s) · %s", strings.TrimPrefix(ext, "."), sizeStr)
	default:
		return summarizePreview(string(rawSample))
	}
}

func extractDocText(fullPath, ext string, size int64, maxChars int) string {
	ext = strings.ToLower(ext)
	if maxChars <= 0 {
		maxChars = 16000
	}
	switch ext {
	case ".md", ".txt", ".csv", ".json":
		f, err := os.Open(fullPath)
		if err != nil {
			return ""
		}
		defer f.Close()
		raw, err := io.ReadAll(io.LimitReader(f, 512<<10))
		if err != nil {
			return ""
		}
		return truncateRunes(string(raw), maxChars)
	case ".docx":
		text, err := extractDocxText(fullPath, maxChars)
		if err != nil || strings.TrimSpace(text) == "" {
			return fmt.Sprintf("Word 文档（%s）暂未提取到正文，可点击「用其他应用打开」查看完整排版。", formatBytesHuman(size))
		}
		return text
	case ".xlsx":
		text, err := extractXlsxText(fullPath, maxChars)
		if err != nil || strings.TrimSpace(text) == "" {
			return fmt.Sprintf("Excel 表格（%s）暂未提取到单元格文本，可点击「用其他应用打开」查看。", formatBytesHuman(size))
		}
		return text
	case ".pptx":
		text, err := extractPptxText(fullPath, maxChars)
		if err != nil || strings.TrimSpace(text) == "" {
			return fmt.Sprintf("PPT 演示文稿（%s）暂未提取到文本，可点击「用其他应用打开」查看。", formatBytesHuman(size))
		}
		return text
	case ".pdf":
		pages := estimatePdfPageCount(fullPath)
		if pages > 0 {
			return fmt.Sprintf("PDF 文档 · 共约 %d 页 · %s\n\n支持直接在应用内分页浏览，或使用本机 PDF 阅读器打开。", pages, formatBytesHuman(size))
		}
		return fmt.Sprintf("PDF 文档 · %s\n\n支持直接在应用内分页浏览，或使用本机 PDF 阅读器打开。", formatBytesHuman(size))
	case ".doc", ".xls", ".ppt":
		return fmt.Sprintf("旧版 Office 二进制格式 (%s · %s)，请点击下方「用其他应用打开」在 WPS / Office 中查看。", ext, formatBytesHuman(size))
	case ".png", ".jpg", ".jpeg", ".webp":
		return fmt.Sprintf("图片文件（%s · %s）", strings.TrimPrefix(ext, "."), formatBytesHuman(size))
	default:
		return fmt.Sprintf("文件（%s · %s）", ext, formatBytesHuman(size))
	}
}

func truncateRunes(s string, maxChars int) string {
	s = strings.TrimSpace(s)
	runes := []rune(s)
	if len(runes) <= maxChars {
		return s
	}
	return strings.TrimSpace(string(runes[:maxChars])) + "\n\n...（内容过长，已截断）"
}

func estimatePdfPageCount(fullPath string) int {
	f, err := os.Open(fullPath)
	if err != nil {
		return 0
	}
	defer f.Close()
	raw, err := io.ReadAll(io.LimitReader(f, 4<<20))
	if err != nil {
		return 0
	}
	totalPageTags := bytes.Count(raw, []byte("/Type /Page")) + bytes.Count(raw, []byte("/Type/Page"))
	pagesTags := bytes.Count(raw, []byte("/Type /Pages")) + bytes.Count(raw, []byte("/Type/Pages"))
	n := totalPageTags - pagesTags
	if n < 0 {
		return 0
	}
	return n
}

func extractDocxText(fullPath string, maxChars int) (string, error) {
	zr, err := zip.OpenReader(fullPath)
	if err != nil {
		return "", err
	}
	defer zr.Close()

	for _, f := range zr.File {
		if f.Name != "word/document.xml" {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			return "", err
		}
		defer rc.Close()
		dec := xml.NewDecoder(io.LimitReader(rc, 8<<20))
		var out strings.Builder
		var cellInRow int
		inRow := false
		for {
			tok, err := dec.Token()
			if err == io.EOF {
				break
			}
			if err != nil {
				break
			}
			switch el := tok.(type) {
			case xml.StartElement:
				switch el.Name.Local {
				case "tr":
					inRow = true
					cellInRow = 0
				case "tc":
					if inRow && cellInRow > 0 {
						out.WriteString(" | ")
					}
					cellInRow++
				case "tab":
					out.WriteString("\t")
				case "br":
					out.WriteString("\n")
				}
			case xml.EndElement:
				switch el.Name.Local {
				case "p":
					if !inRow {
						out.WriteString("\n")
					} else {
						out.WriteString(" ")
					}
				case "tr":
					inRow = false
					out.WriteString("\n")
				}
			case xml.CharData:
				out.WriteString(string(el))
			}
			if out.Len() > maxChars*4 {
				break
			}
		}
		return truncateRunes(out.String(), maxChars), nil
	}
	return "", os.ErrNotExist
}

func extractXlsxText(fullPath string, maxChars int) (string, error) {
	zr, err := zip.OpenReader(fullPath)
	if err != nil {
		return "", err
	}
	defer zr.Close()

	var sharedStrings []string
	var sheets []*zip.File
	for _, f := range zr.File {
		switch {
		case f.Name == "xl/sharedStrings.xml":
			sharedStrings = parseXlsxSharedStrings(f)
		case strings.HasPrefix(f.Name, "xl/worksheets/sheet") && strings.HasSuffix(f.Name, ".xml"):
			sheets = append(sheets, f)
		}
	}
	sort.Slice(sheets, func(i, j int) bool {
		return sheets[i].Name < sheets[j].Name
	})

	var out strings.Builder
	for idx, sf := range sheets {
		rows := parseXlsxSheetRows(sf, sharedStrings, 150)
		if len(rows) == 0 {
			continue
		}
		if idx > 0 {
			out.WriteString("\n")
		}
		out.WriteString(fmt.Sprintf("### 工作表 %d (%s)\n\n", idx+1, filepath.Base(sf.Name)))
		maxCols := 0
		for _, r := range rows {
			if len(r) > maxCols {
				maxCols = len(r)
			}
		}
		if maxCols > 10 {
			maxCols = 10
		}
		if maxCols > 0 {
			// Render as Markdown table for crisp UI viewing + LLM comprehension
			header := padRow(rows[0], maxCols)
			out.WriteString("| " + strings.Join(header, " | ") + " |\n")
			sep := make([]string, maxCols)
			for i := range sep {
				sep[i] = "---"
			}
			out.WriteString("| " + strings.Join(sep, " | ") + " |\n")
			for _, r := range rows[1:] {
				out.WriteString("| " + strings.Join(padRow(r, maxCols), " | ") + " |\n")
			}
			out.WriteString("\n")
		}
		if out.Len() > maxChars*4 {
			break
		}
	}
	return truncateRunes(out.String(), maxChars), nil
}

func padRow(r []string, cols int) []string {
	out := make([]string, cols)
	for i := 0; i < cols; i++ {
		if i < len(r) {
			val := strings.ReplaceAll(strings.TrimSpace(r[i]), "|", "\\|")
			val = strings.ReplaceAll(val, "\n", " ")
			out[i] = val
		}
	}
	return out
}

func parseXlsxSharedStrings(f *zip.File) []string {
	rc, err := f.Open()
	if err != nil {
		return nil
	}
	defer rc.Close()
	dec := xml.NewDecoder(io.LimitReader(rc, 4<<20))
	var out []string
	var cur strings.Builder
	inSi := false
	inText := false
	for {
		tok, err := dec.Token()
		if err != nil {
			break
		}
		switch el := tok.(type) {
		case xml.StartElement:
			if el.Name.Local == "si" {
				inSi = true
				cur.Reset()
			} else if inSi && el.Name.Local == "t" {
				inText = true
			}
		case xml.EndElement:
			if el.Name.Local == "t" {
				inText = false
			} else if el.Name.Local == "si" {
				inSi = false
				out = append(out, cur.String())
			}
		case xml.CharData:
			if inSi && inText {
				cur.WriteString(string(el))
			}
		}
	}
	return out
}

func parseXlsxSheetRows(f *zip.File, shared []string, maxRows int) [][]string {
	rc, err := f.Open()
	if err != nil {
		return nil
	}
	defer rc.Close()
	dec := xml.NewDecoder(io.LimitReader(rc, 8<<20))
	var rows [][]string
	var curRow []string
	var curType string
	var curVal strings.Builder
	inVal := false
	for {
		tok, err := dec.Token()
		if err != nil {
			break
		}
		switch el := tok.(type) {
		case xml.StartElement:
			switch el.Name.Local {
			case "row":
				curRow = nil
			case "c":
				curType = ""
				for _, a := range el.Attr {
					if a.Name.Local == "t" {
						curType = a.Value
					}
				}
				curVal.Reset()
			case "v", "t":
				inVal = true
			}
		case xml.EndElement:
			switch el.Name.Local {
			case "v", "t":
				inVal = false
			case "c":
				raw := strings.TrimSpace(curVal.String())
				if curType == "s" {
					if idx, err := strconv.Atoi(raw); err == nil && idx >= 0 && idx < len(shared) {
						raw = shared[idx]
					}
				}
				curRow = append(curRow, raw)
			case "row":
				hasNonEmpty := false
				for _, c := range curRow {
					if strings.TrimSpace(c) != "" {
						hasNonEmpty = true
						break
					}
				}
				if hasNonEmpty {
					rows = append(rows, curRow)
				}
				if len(rows) >= maxRows {
					return rows
				}
			}
		case xml.CharData:
			if inVal {
				curVal.WriteString(string(el))
			}
		}
	}
	return rows
}

func extractPptxText(fullPath string, maxChars int) (string, error) {
	zr, err := zip.OpenReader(fullPath)
	if err != nil {
		return "", err
	}
	defer zr.Close()

	var slides []*zip.File
	for _, f := range zr.File {
		if strings.HasPrefix(f.Name, "ppt/slides/slide") && strings.HasSuffix(f.Name, ".xml") {
			slides = append(slides, f)
		}
	}
	sort.Slice(slides, func(i, j int) bool {
		return slides[i].Name < slides[j].Name
	})

	var out strings.Builder
	for idx, sf := range slides {
		rc, err := sf.Open()
		if err != nil {
			continue
		}
		dec := xml.NewDecoder(io.LimitReader(rc, 2<<20))
		var slideBuf strings.Builder
		inT := false
		for {
			tok, err := dec.Token()
			if err != nil {
				break
			}
			switch el := tok.(type) {
			case xml.StartElement:
				if el.Name.Local == "t" {
					inT = true
				}
			case xml.EndElement:
				if el.Name.Local == "t" {
					inT = false
					slideBuf.WriteString(" ")
				} else if el.Name.Local == "p" {
					slideBuf.WriteString("\n")
				}
			case xml.CharData:
				if inT {
					slideBuf.WriteString(string(el))
				}
			}
		}
		rc.Close()
		txt := strings.TrimSpace(slideBuf.String())
		if txt != "" {
			out.WriteString(fmt.Sprintf("### 第 %d 页\n%s\n\n", idx+1, txt))
		}
		if out.Len() > maxChars*4 {
			break
		}
	}
	return truncateRunes(out.String(), maxChars), nil
}

func (s *server) getDocRaw(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("path"))
	if q == "" {
		errorJSON(w, http.StatusBadRequest, "path_required")
		return
	}
	if q == "MEMORY.md" || q == "memory/MEMORY.md" {
		if s.docs.memoryFile == "" {
			errorJSON(w, http.StatusNotFound, "doc_not_found")
			return
		}
		_ = http.NewResponseController(w).SetWriteDeadline(time.Time{})
		w.Header().Set("Content-Type", "text/markdown; charset=utf-8")
		w.Header().Set("Content-Disposition", `inline; filename="MEMORY.md"`)
		http.ServeFile(w, r, s.docs.memoryFile)
		return
	}
	clean, err := sanitizeRelPath(q)
	if err != nil {
		errorJSON(w, http.StatusBadRequest, "invalid_doc_path")
		return
	}
	full, err := s.docs.resolveAssistantFile(clean)
	if err != nil {
		errorJSON(w, http.StatusBadRequest, "invalid_doc_path")
		return
	}
	info, err := os.Stat(full)
	if err != nil || info.IsDir() {
		errorJSON(w, http.StatusNotFound, "doc_not_found")
		return
	}
	_ = http.NewResponseController(w).SetWriteDeadline(time.Time{})
	ext := strings.ToLower(filepath.Ext(clean))
	w.Header().Set("Content-Type", mimeTypeForExt(ext))
	safeName := strings.ReplaceAll(filepath.Base(clean), `"`, "")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`inline; filename="%s"`, safeName))
	http.ServeFile(w, r, full)
}
