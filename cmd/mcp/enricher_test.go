package main

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestApplyCelPresetAcctnum12Shapes(t *testing.T) {
	cases := []struct {
		in, want string
		drop     bool
	}{
		{"7707-938199-1", "770793819901", false},
		{"7707-938199-12", "770793819912", false},
		{"770793819901ABC", "770793819901", false},
		{"770793819901", "770793819901", false},
		{"", "", true},
	}
	for _, tc := range cases {
		got, drop := applyCelPreset("acctnum-12", tc.in)
		if got != tc.want || drop != tc.drop {
			t.Fatalf("%q: got %q drop=%v, want %q drop=%v", tc.in, got, drop, tc.want, tc.drop)
		}
	}
}

func TestApplyCelPresetIdentityAndDigitsOnly(t *testing.T) {
	got, drop := applyCelPreset("identity", "7707-938199-1")
	if drop || got != "7707-938199-1" {
		t.Fatalf("identity: %q %v", got, drop)
	}
	got, drop = applyCelPreset("digits-only", "7707-938199-1")
	if drop || got != "77079381991" {
		t.Fatalf("digits-only 13-char (no pad): %q %v", got, drop)
	}
	got, drop = applyCelPreset("digits-only", "770793819901ABC")
	if drop || got != "770793819901" {
		t.Fatalf("digits-only mixed: %q %v", got, drop)
	}
}

func TestBuildEnricherJobRequiresFields(t *testing.T) {
	_, err := buildEnricherJob(map[string]any{"input_topic": "com.optimum.events.example.json"})
	if err == nil {
		t.Fatal("expected error")
	}
}

func TestBuildEnricherJobDefaultsPausedAndPrefixWarning(t *testing.T) {
	out, err := buildEnricherJob(map[string]any{
		"input_topic":  "com.optimum.events.it.csg.osp.json",
		"lookup_topic": "net.optimum.fixed.monitoring.network.access.custdata.acctnum.json",
		"key_field":    "AccountNum",
		"source":       "csg",
		"output_topic": "net.optimum.other.enriched.json",
		"cel_preset":   "identity",
	})
	if err != nil {
		t.Fatal(err)
	}
	job := out["job"].(map[string]any)
	if job["desiredState"] != "PAUSED" {
		t.Fatalf("desiredState: %v", job["desiredState"])
	}
	cfg := job["jsonEnricherConfig"].(map[string]any)
	if cfg["joinKeyCel"] != celIdentity {
		t.Fatalf("cel: %v", cfg["joinKeyCel"])
	}
	warns := out["warnings"].([]string)
	found := false
	for _, w := range warns {
		if strings.Contains(w, "net.optimum.experimental.streamlens.streammux.") {
			found = true
		}
	}
	if !found {
		t.Fatalf("expected output prefix warning, got %v", warns)
	}
}

func TestNormalizeKeyPreviewSynthetic(t *testing.T) {
	out, err := normalizeKeyPreview(map[string]any{
		"cel_preset": "acctnum-12",
		"samples":    []any{"7707-938199-1", "770793819901ABC"},
	})
	if err != nil {
		t.Fatal(err)
	}
	results := out["results"].([]map[string]any)
	if len(results) != 2 {
		t.Fatalf("len=%d", len(results))
	}
	if results[0]["output"] != "770793819901" {
		t.Fatalf("first: %v", results[0])
	}
	if results[1]["output"] != "770793819901" {
		t.Fatalf("second: %v", results[1])
	}
}

func TestGetEnricherTemplateIncludesDeployedCel(t *testing.T) {
	raw, err := json.Marshal(getEnricherTemplatePayload())
	if err != nil {
		t.Fatal(err)
	}
	s := string(raw)
	if !strings.Contains(s, "json-enricher-csg-osp-1") {
		t.Fatal("missing deployed job id")
	}
	if !strings.Contains(s, "docs/enricher-guide.md") {
		t.Fatal("missing guide path")
	}
}

func TestToolListIncludesEnricherHelpers(t *testing.T) {
	want := []string{"get_enricher_template", "build_enricher_job", "normalize_key_preview"}
	seen := map[string]bool{}
	for _, tool := range toolList() {
		if name, ok := tool["name"].(string); ok {
			seen[name] = true
		}
	}
	for _, name := range want {
		if !seen[name] {
			t.Fatalf("toolList() missing %s", name)
		}
		if isWriteTool(name) {
			t.Fatalf("%s should be read-only", name)
		}
	}
}

func TestHandleToolCallEnricherRequiresDocsScope(t *testing.T) {
	s := newTestMCPServer(t, []string{"mcp", "read"})
	_, err := s.handleToolCall(t.Context(), toolsCallParams{Name: "get_enricher_template"}, s.testBearer)
	if err == nil || !strings.Contains(err.Error(), "missing scope") {
		t.Fatalf("expected missing docs scope, got %v", err)
	}
}

func TestHandleToolCallEnricherTemplateWithDocs(t *testing.T) {
	s := newTestMCPServer(t, []string{"mcp", "docs"})
	body, err := s.handleToolCall(t.Context(), toolsCallParams{Name: "get_enricher_template"}, s.testBearer)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(body, "acctnum-12") {
		t.Fatalf("unexpected body: %s", body)
	}
}

func TestKnowledgeEmbedsEnricherGuide(t *testing.T) {
	ks := loadKnowledge()
	e, ok := ks.getDoc("docs/enricher-guide.md")
	if !ok || !strings.Contains(e.Content, "Create an enrichment job") {
		t.Fatalf("enricher-guide not embedded: ok=%v", ok)
	}
	hits := ks.searchDocs("JSON_ENRICHER")
	found := false
	for _, h := range hits {
		if h.Path == "docs/enricher-guide.md" {
			found = true
		}
	}
	if !found {
		t.Fatalf("search_docs missed enricher-guide: %+v", hits)
	}
}
