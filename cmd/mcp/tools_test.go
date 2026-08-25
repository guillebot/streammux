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
