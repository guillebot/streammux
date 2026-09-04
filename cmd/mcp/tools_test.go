package main

import "testing"

func TestReadProxyPathListActivityBuildsQueryString(t *testing.T) {
	path, err := readProxyPath(toolsCallParams{
		Name: "list_activity",
		Arguments: map[string]any{
			"limit":      "50",
			"job_id":     "job-1",
			"event_type": "PAUSED",
			"actor":      "jsolarin",
		},
	})
	if err != nil {
		t.Fatalf("readProxyPath() error = %v", err)
	}
	if path != "/activity?actor=jsolarin&eventType=PAUSED&jobId=job-1&limit=50" {
		t.Fatalf("unexpected path: %q", path)
	}
}

func TestReadProxyPathListActivityWithoutFilters(t *testing.T) {
	path, err := readProxyPath(toolsCallParams{Name: "list_activity"})
	if err != nil {
		t.Fatalf("readProxyPath() error = %v", err)
	}
	if path != "/activity" {
		t.Fatalf("unexpected path: %q", path)
	}
}

func TestReadProxyPathListActivityRepeatsCommaSeparatedEventType(t *testing.T) {
	path, err := readProxyPath(toolsCallParams{
		Name: "list_activity",
		Arguments: map[string]any{
			"event_type": "PAUSED, STARTED",
		},
	})
	if err != nil {
		t.Fatalf("readProxyPath() error = %v", err)
	}
	if path != "/activity?eventType=PAUSED&eventType=STARTED" {
		t.Fatalf("unexpected path: %q", path)
	}
}

func TestReadProxyPathListActivityAcceptsEventTypeArray(t *testing.T) {
	path, err := readProxyPath(toolsCallParams{
		Name: "list_activity",
		Arguments: map[string]any{
			"event_type": []any{"PAUSED", "STARTED"},
		},
	})
	if err != nil {
		t.Fatalf("readProxyPath() error = %v", err)
	}
	if path != "/activity?eventType=PAUSED&eventType=STARTED" {
		t.Fatalf("unexpected path: %q", path)
	}
}

func TestIsWriteToolExcludesListActivity(t *testing.T) {
	if isWriteTool("list_activity") {
		t.Fatal("list_activity should be read-only")
	}
}

func TestToolListIncludesListActivity(t *testing.T) {
	tools := toolList()
	found := false
	for _, tool := range tools {
		if tool["name"] == "list_activity" {
			found = true
			break
		}
	}
	if !found {
		t.Fatal("toolList() missing list_activity")
	}
}

func TestIsWriteToolIncludesRenameJob(t *testing.T) {
	if !isWriteTool("rename_job") {
		t.Fatal("rename_job should be a write tool")
	}
}

func TestToolListIncludesRenameJob(t *testing.T) {
	tools := toolList()
	for _, tool := range tools {
		if tool["name"] == "rename_job" {
			return
		}
	}
	t.Fatal("toolList() missing rename_job")
}

func TestReadProxyPathGetJobSchema(t *testing.T) {
	path, err := readProxyPath(toolsCallParams{Name: "get_job_schema"})
	if err != nil {
		t.Fatalf("readProxyPath() error = %v", err)
	}
	if path != "/jobs/schema" {
		t.Fatalf("unexpected path: %q", path)
	}
}

func TestReadProxyPathCatalogMeta(t *testing.T) {
	health, err := readProxyPath(toolsCallParams{Name: "get_catalog_health"})
	if err != nil {
		t.Fatalf("get_catalog_health: %v", err)
	}
	if health != "catalog:/catalog/health" {
		t.Fatalf("unexpected health path: %q", health)
	}
	settings, err := readProxyPath(toolsCallParams{Name: "get_catalog_settings"})
	if err != nil {
		t.Fatalf("get_catalog_settings: %v", err)
	}
	if settings != "catalog:/catalog/settings" {
		t.Fatalf("unexpected settings path: %q", settings)
	}
}

func TestIsWriteToolExcludesValidateJob(t *testing.T) {
	if isWriteTool("validate_job") {
		t.Fatal("validate_job should be read-only")
	}
}

func TestToolListIncludesNewReadTools(t *testing.T) {
	want := []string{"get_job_schema", "validate_job", "get_catalog_health", "get_catalog_settings"}
	tools := toolList()
	seen := map[string]bool{}
	for _, tool := range tools {
		if name, ok := tool["name"].(string); ok {
			seen[name] = true
		}
	}
	for _, name := range want {
		if !seen[name] {
			t.Fatalf("toolList() missing %s", name)
		}
	}
}
