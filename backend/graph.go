package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

type graphNode struct {
	ID        string `json:"id"`
	Kind      string `json:"kind"`
	Category  string `json:"category"`
	Label     string `json:"label"`
	Subtitle  string `json:"subtitle,omitempty"`
	Preview   string `json:"preview,omitempty"`
	DocPath   string `json:"doc_path,omitempty"`
	UpdatedAt string `json:"updated_at,omitempty"`
	Weight    int    `json:"weight"`
}

type graphEdge struct {
	Source string `json:"source"`
	Target string `json:"target"`
	Kind   string `json:"kind"`
	Label  string `json:"label,omitempty"`
}

type graphStats struct {
	NodeCount  int `json:"node_count"`
	EdgeCount  int `json:"edge_count"`
	DocCount   int `json:"doc_count"`
	EntryCount int `json:"entry_count"`
	TagCount   int `json:"tag_count"`
}

type knowledgeGraphResponse struct {
	Nodes []graphNode `json:"nodes"`
	Edges []graphEdge `json:"edges"`
	Stats graphStats  `json:"stats"`
}

var (
	wikiLinkPattern    = regexp.MustCompile(`\[\[([^\[\]|#]+)(?:[^\]]*)\]\]`)
	mdLinkPattern      = regexp.MustCompile(`\[[^\]]+\]\(([^)\s]+)\)`)
	inlineTagPattern   = regexp.MustCompile(`(?:^|[\s，。；：(（])#([\p{Han}A-Za-z0-9_-]{1,20})`)
	memoryHeadingRegex = regexp.MustCompile(`(?m)^(?:#{1,3}|§+)\s*(.+)$`)
)

func (s *server) getGraph(w http.ResponseWriter, r *http.Request) {
	graph, err := s.buildKnowledgeGraph(r.Context())
	if err != nil {
		errorJSON(w, http.StatusInternalServerError, "graph_failed")
		return
	}
	writeJSON(w, http.StatusOK, graph)
}

func (s *server) buildKnowledgeGraph(ctx context.Context) (knowledgeGraphResponse, error) {
	nodes := map[string]graphNode{}
	edges := make([]graphEdge, 0, 128)
	edgeSeen := map[string]bool{}

	addNode := func(n graphNode) {
		if existing, ok := nodes[n.ID]; ok {
			if existing.Preview == "" && n.Preview != "" {
				existing.Preview = n.Preview
			}
			if existing.DocPath == "" && n.DocPath != "" {
				existing.DocPath = n.DocPath
			}
			nodes[n.ID] = existing
			return
		}
		nodes[n.ID] = n
	}

	addEdge := func(src, dst, kind, label string) {
		if src == "" || dst == "" || src == dst {
			return
		}
		if _, ok := nodes[src]; !ok {
			return
		}
		if _, ok := nodes[dst]; !ok {
			return
		}
		key := src + "|" + dst + "|" + kind
		rev := dst + "|" + src + "|" + kind
		if edgeSeen[key] || edgeSeen[rev] {
			return
		}
		edgeSeen[key] = true
		edges = append(edges, graphEdge{
			Source: src,
			Target: dst,
			Kind:   kind,
			Label:  label,
		})
	}

	rootID := "hub:root"
	addNode(graphNode{
		ID:       rootID,
		Kind:     "root",
		Category: "root",
		Label:    "个人知识图谱",
		Subtitle: "记忆 · 笔记 · 日记 · 档案",
		Preview:  "整合个人助手文档、长期记忆、随手记看板与上传文档的关联网络。",
		Weight:   10,
	})

	hubMeta := map[string]struct {
		label    string
		subtitle string
	}{
		"memory": {label: "核心记忆", subtitle: "Hermes 长期记忆与画像"},
		"note":   {label: "笔记灵感", subtitle: "日常笔记、荣誉与阅读积累"},
		"diary":  {label: "日记归档", subtitle: "按日记录与复盘"},
		"data":   {label: "结构档案", subtitle: "跑步、睡眠等 JSON 追踪数据"},
		"upload": {label: "文档附件", subtitle: "Word / PDF / Excel 等资料"},
		"entry":  {label: "随手记看板", subtitle: "同步记录与待办事项"},
	}

	ensureHub := func(cat string) string {
		hubID := "hub:" + cat
		if _, ok := nodes[hubID]; ok {
			return hubID
		}
		meta, ok := hubMeta[cat]
		if !ok {
			meta.label = cat
			meta.subtitle = "知识分类"
		}
		addNode(graphNode{
			ID:       hubID,
			Kind:     "hub",
			Category: cat,
			Label:    meta.label,
			Subtitle: meta.subtitle,
			Preview:  meta.subtitle,
			Weight:   6,
		})
		addEdge(hubID, rootID, "belongs_to", "")
		return hubID
	}

	docs, err := s.docs.list()
	if err != nil {
		return knowledgeGraphResponse{}, err
	}

	titleToDocID := map[string]string{}
	pathToDocID := map[string]string{}
	docCount := 0
	for _, d := range docs {
		docID := "doc:" + d.Path
		docCount++
		addNode(graphNode{
			ID:        docID,
			Kind:      d.Category,
			Category:  d.Category,
			Label:     d.Title,
			Subtitle:  d.Path,
			Preview:   d.Preview,
			DocPath:   d.Path,
			UpdatedAt: d.UpdatedAt,
			Weight:    2,
		})
		hubID := ensureHub(d.Category)
		addEdge(docID, hubID, "belongs_to", "")
		titleToDocID[strings.ToLower(d.Title)] = docID
		pathToDocID[strings.ToLower(d.Path)] = docID
		pathToDocID[strings.ToLower(filepath.Base(d.Path))] = docID
	}

	tagDocs := map[string][]string{}
	ensureTagNode := func(tag string) string {
		cleaned := strings.TrimPrefix(strings.TrimSpace(tag), "#")
		if cleaned == "" {
			return ""
		}
		id := "tag:" + cleaned
		addNode(graphNode{
			ID:       id,
			Kind:     "tag",
			Category: "tag",
			Label:    "#" + cleaned,
			Subtitle: "主题标签",
			Preview:  fmt.Sprintf("包含 #%s 主题的关联文档与记录", cleaned),
			Weight:   3,
		})
		return id
	}

	// Parse Markdown links, hashtags, and MEMORY.md sections cleanly
	for _, d := range docs {
		docID := "doc:" + d.Path
		ext := strings.ToLower(filepath.Ext(d.Path))
		if ext != ".md" {
			continue
		}
		var raw []byte
		if (d.Path == "memory/MEMORY.md" || d.Path == "MEMORY.md") && s.docs.memoryFile != "" {
			raw, _ = os.ReadFile(s.docs.memoryFile)
		} else if full, err := s.docs.resolveAssistantFile(d.Path); err == nil {
			raw, _ = os.ReadFile(full)
		}
		if len(raw) == 0 {
			continue
		}
		text := string(raw)

		if d.Path == "memory/MEMORY.md" || d.Path == "MEMORY.md" {
			addMemorySectionNodes(text, docID, ensureHub("memory"), titleToDocID, addNode, addEdge)
		}

		for _, m := range wikiLinkPattern.FindAllStringSubmatch(text, 24) {
			target := strings.ToLower(strings.TrimSpace(m[1]))
			if tid, ok := titleToDocID[target]; ok {
				addEdge(docID, tid, "linked_doc", "引用")
			} else if tid, ok := pathToDocID[target]; ok {
				addEdge(docID, tid, "linked_doc", "引用")
			}
		}
		for _, m := range mdLinkPattern.FindAllStringSubmatch(text, 24) {
			target := strings.TrimPrefix(strings.TrimSpace(m[1]), "./")
			targetLower := strings.ToLower(target)
			if strings.HasPrefix(targetLower, "http://") || strings.HasPrefix(targetLower, "https://") {
				continue
			}
			if tid, ok := pathToDocID[targetLower]; ok {
				addEdge(docID, tid, "linked_doc", "关联")
			} else if tid, ok := pathToDocID[strings.ToLower(filepath.Base(target))]; ok {
				addEdge(docID, tid, "linked_doc", "关联")
			}
		}
		seenDocTags := map[string]bool{}
		for _, m := range inlineTagPattern.FindAllStringSubmatch(text, 24) {
			tag := strings.TrimSpace(m[1])
			if tag == "" || seenDocTags[tag] {
				continue
			}
			seenDocTags[tag] = true
			tagID := ensureTagNode(tag)
			if tagID != "" {
				tagDocs[tag] = append(tagDocs[tag], docID)
				addEdge(docID, tagID, "tagged", "")
			}
		}
	}

	// Entries + tags from PostgreSQL
	entryCount := 0
	if s.entries != nil {
		items, err := s.entries.List(ctx, entryFilter{Limit: 80})
		if err == nil && len(items) > 0 {
			entryHub := ensureHub("entry")
			for idx, it := range items {
				if idx >= 36 && !it.Pinned {
					continue
				}
				entryCount++
				entryID := "entry:" + it.ID
				title := strings.TrimSpace(it.Title)
				if title == "" {
					title = summarizePreview(it.Content)
				}
				if title == "" {
					title = "随手记"
				}
				docPath := ""
				if it.DocPath != nil {
					docPath = strings.TrimSpace(*it.DocPath)
				}
				addNode(graphNode{
					ID:        entryID,
					Kind:      "entry",
					Category:  "entry",
					Label:     truncateRunes(title, 26),
					Subtitle:  it.Kind,
					Preview:   summarizePreview(it.Content),
					DocPath:   docPath,
					UpdatedAt: it.UpdatedAt.Format("2006-01-02"),
					Weight:    2,
				})
				addEdge(entryID, entryHub, "belongs_to", "")
				if docPath != "" {
					if docID, ok := pathToDocID[strings.ToLower(docPath)]; ok {
						addEdge(entryID, docID, "linked_doc", "同步文档")
					}
				}
				for _, t := range it.Tags {
					tagID := ensureTagNode(t)
					if tagID != "" {
						tagDocs[t] = append(tagDocs[t], entryID)
						addEdge(entryID, tagID, "tagged", "")
					}
				}
			}
		}
	}

	// Connect orphan tags to root hub so the graph never floats disconnected
	tagCount := 0
	for id, n := range nodes {
		if n.Kind == "tag" {
			tagCount++
			addEdge(id, rootID, "tagged", "")
		}
	}

	// Compute node degree-based weights cleanly
	degree := map[string]int{}
	for _, e := range edges {
		degree[e.Source]++
		degree[e.Target]++
	}
	nodeList := make([]graphNode, 0, len(nodes))
	for id, n := range nodes {
		w := n.Weight + degree[id]
		if w > 18 {
			w = 18
		}
		n.Weight = w
		nodeList = append(nodeList, n)
	}
	sort.Slice(nodeList, func(i, j int) bool {
		if nodeList[i].Kind == "root" {
			return true
		}
		if nodeList[j].Kind == "root" {
			return false
		}
		if nodeList[i].Weight != nodeList[j].Weight {
			return nodeList[i].Weight > nodeList[j].Weight
		}
		return nodeList[i].ID < nodeList[j].ID
	})

	return knowledgeGraphResponse{
		Nodes: nodeList,
		Edges: edges,
		Stats: graphStats{
			NodeCount:  len(nodeList),
			EdgeCount:  len(edges),
			DocCount:   docCount,
			EntryCount: entryCount,
			TagCount:   tagCount,
		},
	}, nil
}

func addMemorySectionNodes(
	raw string,
	memDocID string,
	memHubID string,
	titleToDocID map[string]string,
	addNode func(graphNode),
	addEdge func(string, string, string, string),
) {
	sections := memoryHeadingRegex.FindAllStringSubmatchIndex(raw, 12)
	for i, loc := range sections {
		title := strings.TrimSpace(raw[loc[2]:loc[3]])
		if title == "" {
			continue
		}
		end := len(raw)
		if i+1 < len(sections) {
			end = sections[i+1][0]
		}
		body := strings.TrimSpace(raw[loc[1]:end])
		secID := fmt.Sprintf("mem:%d", i)
		addNode(graphNode{
			ID:       secID,
			Kind:     "memory",
			Category: "memory",
			Label:    truncateRunes(title, 28),
			Subtitle: "MEMORY.md 记忆章节",
			Preview:  summarizePreview(body),
			DocPath:  "memory/MEMORY.md",
			Weight:   4,
		})
		addEdge(secID, memDocID, "memory_section", "章节")
		addEdge(secID, memHubID, "belongs_to", "")
		titleLower := strings.ToLower(title)
		for docTitle, docID := range titleToDocID {
			if len([]rune(docTitle)) >= 2 && (strings.Contains(titleLower, docTitle) || strings.Contains(docTitle, titleLower)) {
				addEdge(secID, docID, "related", "相关")
			}
		}
	}
}
