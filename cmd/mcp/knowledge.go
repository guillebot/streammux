package main

import (
	"encoding/json"
	"io/fs"
	"sort"
	"strings"

	streammux "gitlab.com/dmr4013905/techarchitecture/techarchitecture/streammux"
)

const uriScheme = "streammux://"

type docEntry struct {
	Path    string
	Title   string
	Content string
	MIME    string
}

type knowledgeStore struct {
	docs       []docEntry
	openAPI    docEntry
	byPath     map[string]docEntry
	schemaDefs map[string]json.RawMessage
}

func loadKnowledge() *knowledgeStore {
	ks := &knowledgeStore{
		byPath:     map[string]docEntry{},
		schemaDefs: map[string]json.RawMessage{},
	}
	_ = fs.WalkDir(streammux.KnowledgeFS, ".", func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		b, readErr := streammux.KnowledgeFS.ReadFile(path)
		if readErr != nil {
			return nil
		}
		body := string(b)
		switch {
		case strings.HasSuffix(path, ".md"):
			e := docEntry{Path: path, Title: markdownTitle(body, path), Content: body, MIME: "text/markdown"}
			ks.docs = append(ks.docs, e)
			ks.byPath[path] = e
		case path == "docs/openapi.json":
			e := docEntry{Path: path, Title: "Streammux Job Management API OpenAPI", Content: body, MIME: "application/json"}
			ks.openAPI = e
			ks.byPath[path] = e
			ks.loadOpenAPISchemas(body)
		}
		return nil
	})
	sort.Slice(ks.docs, func(i, j int) bool { return ks.docs[i].Path < ks.docs[j].Path })
	return ks
}

func (k *knowledgeStore) loadOpenAPISchemas(body string) {
	var doc struct {
		Components struct {
			Schemas map[string]json.RawMessage `json:"schemas"`
		} `json:"components"`
	}
	if err := json.Unmarshal([]byte(body), &doc); err != nil {
		return
	}
	for name, def := range doc.Components.Schemas {
		k.schemaDefs[strings.ToLower(name)] = def
	}
}

func (k *knowledgeStore) listDocs() []map[string]string {
	out := make([]map[string]string, 0, len(k.docs))
	for _, d := range k.docs {
		out = append(out, map[string]string{"path": d.Path, "title": d.Title})
	}
	return out
}

func (k *knowledgeStore) getDoc(path string) (docEntry, bool) {
	path = strings.TrimPrefix(strings.TrimSpace(path), "/")
	if e, ok := k.byPath[path]; ok {
		return e, true
	}
	if e, ok := k.byPath["docs/"+path]; ok {
		return e, true
	}
	if rest, ok := strings.CutPrefix(path, "streammux://"); ok {
		return k.getDoc(strings.TrimLeft(rest, "/"))
	}
	return docEntry{}, false
}

type searchHit struct {
	Path    string `json:"path"`
	Title   string `json:"title"`
	Score   int    `json:"score"`
	Snippet string `json:"snippet"`
}

func (k *knowledgeStore) searchDocs(query string) []searchHit {
	q := strings.ToLower(strings.TrimSpace(query))
	if q == "" {
		return nil
	}
	var hits []searchHit
	for _, d := range k.docs {
		lc := strings.ToLower(d.Content)
		score := strings.Count(lc, q)
		if strings.Contains(strings.ToLower(d.Title), q) {
			score += 5
		}
		if score == 0 {
			continue
		}
		hits = append(hits, searchHit{Path: d.Path, Title: d.Title, Score: score, Snippet: snippet(d.Content, lc, q)})
	}
	sort.Slice(hits, func(i, j int) bool {
		if hits[i].Score != hits[j].Score {
			return hits[i].Score > hits[j].Score
		}
		return hits[i].Path < hits[j].Path
	})
	if len(hits) > 10 {
		hits = hits[:10]
	}
	return hits
}

func (k *knowledgeStore) listSchemaNames() []string {
	names := make([]string, 0, len(k.schemaDefs))
	for n := range k.schemaDefs {
		names = append(names, n)
	}
	sort.Strings(names)
	return names
}

func (k *knowledgeStore) getSchema(name string) (string, bool) {
	n := strings.ToLower(strings.TrimSpace(name))
	if n == "" {
		return "", false
	}
	def, ok := k.schemaDefs[n]
	if !ok {
		for key, val := range k.schemaDefs {
			if strings.Contains(key, n) {
				def, ok = val, true
				break
			}
		}
	}
	if !ok {
		return "", false
	}
	var pretty json.RawMessage
	if err := json.Unmarshal(def, &pretty); err != nil {
		return string(def), true
	}
	b, err := json.MarshalIndent(pretty, "", "  ")
	if err != nil {
		return string(def), true
	}
	return string(b), true
}

func (k *knowledgeStore) getOpenAPI() (string, bool) {
	if k.openAPI.Content == "" {
		return "", false
	}
	return k.openAPI.Content, true
}

func (k *knowledgeStore) resourceList() []map[string]any {
	all := append([]docEntry{}, k.docs...)
	if k.openAPI.Path != "" {
		all = append(all, k.openAPI)
	}
	out := make([]map[string]any, 0, len(all))
	for _, e := range all {
		out = append(out, map[string]any{
			"uri":         uriScheme + "/" + e.Path,
			"name":        e.Title,
			"description": "Streammux documentation: " + e.Path,
			"mimeType":    e.MIME,
		})
	}
	return out
}

func (k *knowledgeStore) resourceByURI(uri string) (docEntry, bool) {
	rest, ok := strings.CutPrefix(uri, uriScheme)
	if !ok {
		return docEntry{}, false
	}
	path := strings.TrimLeft(rest, "/")
	e, ok := k.byPath[path]
	return e, ok
}

func markdownTitle(body, path string) string {
	for _, line := range strings.Split(body, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "# ") {
			return strings.TrimSpace(line[2:])
		}
	}
	return baseName(path)
}

func baseName(path string) string {
	if i := strings.LastIndex(path, "/"); i >= 0 {
		return path[i+1:]
	}
	return path
}

func snippet(content, lc, q string) string {
	idx := strings.Index(lc, q)
	if idx < 0 {
		s := strings.TrimSpace(content)
		if len(s) > 160 {
			return s[:160] + "…"
		}
		return s
	}
	start := idx - 60
	if start < 0 {
		start = 0
	}
	end := idx + len(q) + 100
	if end > len(content) {
		end = len(content)
	}
	out := strings.TrimSpace(content[start:end])
	out = strings.ReplaceAll(out, "\n", " ")
	if start > 0 {
		out = "…" + out
	}
	if end < len(content) {
		out = out + "…"
	}
	return out
}
