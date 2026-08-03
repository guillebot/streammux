package main

import (
	"context"
	"net/http"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"gitlab.com/dmr4013905/techarchitecture/techarchitecture/streammux/internal/auth"
)

func TestHandleToolCallRejectsMissingToken(t *testing.T) {
	s := newTestMCPServer(t, nil)
	_, err := s.handleToolCall(context.Background(), toolsCallParams{Name: "list_jobs"}, "")
	if err == nil || !strings.Contains(err.Error(), "bearer token") {
		t.Fatalf("expected bearer token error, got %v", err)
	}
}

func TestHandleToolCallEnforcesReadScope(t *testing.T) {
	s := newTestMCPServer(t, []string{"mcp", "docs"})
	_, err := s.handleToolCall(context.Background(), toolsCallParams{Name: "list_jobs"}, s.testBearer)
	if err == nil || !strings.Contains(err.Error(), "missing scope") {
		t.Fatalf("expected missing read scope, got %v", err)
	}
}

func TestHandleToolCallEnforcesWriteScope(t *testing.T) {
	s := newTestMCPServer(t, []string{"mcp", "read"})
	_, err := s.handleToolCall(context.Background(), toolsCallParams{
		Name:      "delete_job",
		Arguments: map[string]any{"job_id": "job-1", "apply": true},
	}, s.testBearer)
	if err == nil || !strings.Contains(err.Error(), "missing scope") {
		t.Fatalf("expected missing write scope, got %v", err)
	}
}

func TestHandleToolCallRequiresApplyTrue(t *testing.T) {
	s := newTestMCPServer(t, []string{"mcp", "write", "admin"})
	_, err := s.handleToolCall(context.Background(), toolsCallParams{
		Name:      "delete_job",
		Arguments: map[string]any{"job_id": "job-1"},
	}, s.testBearer)
	if err == nil || !strings.Contains(err.Error(), "apply=true") {
		t.Fatalf("expected apply=true error, got %v", err)
	}

	_, err = s.handleToolCall(context.Background(), toolsCallParams{
		Name:      "delete_job",
		Arguments: map[string]any{"job_id": "job-1", "apply": false},
	}, s.testBearer)
	if err == nil || !strings.Contains(err.Error(), "apply=true") {
		t.Fatalf("expected apply=true error for false, got %v", err)
	}
}

func TestHandleToolCallAdminScopeForTokenList(t *testing.T) {
	s := newTestMCPServer(t, []string{"mcp", "read", "write"})
	_, err := s.handleToolCall(context.Background(), toolsCallParams{Name: "token_list"}, s.testBearer)
	if err == nil || !strings.Contains(err.Error(), "missing scope") {
		t.Fatalf("expected missing admin scope, got %v", err)
	}
}

func TestRequireApplyHelper(t *testing.T) {
	if err := requireApply(nil); err == nil {
		t.Fatal("nil args should fail")
	}
	if err := requireApply(map[string]any{"apply": "true"}); err == nil {
		t.Fatal("string apply should fail")
	}
	if err := requireApply(map[string]any{"apply": true}); err != nil {
		t.Fatalf("apply=true should pass: %v", err)
	}
}

func TestIsWriteToolTable(t *testing.T) {
	writes := []string{
		"create_job", "update_job", "rename_job", "delete_job",
		"pause_job", "resume_job", "restart_job",
		"create_catalog_entry", "update_catalog_entry", "delete_catalog_entry",
		"duplicate_catalog_entry", "push_catalog_entry",
		"token_create", "token_revoke",
	}
	for _, name := range writes {
		if !isWriteTool(name) {
			t.Fatalf("%s should be a write tool", name)
		}
	}
	reads := []string{"list_jobs", "get_job", "list_docs", "get_health", "session", "token_list"}
	for _, name := range reads {
		if isWriteTool(name) {
			t.Fatalf("%s should not be a write tool", name)
		}
	}
}

type testMCPServer struct {
	*mcpServer
	testBearer string
}

func newTestMCPServer(t *testing.T, scopes []string) *testMCPServer {
	t.Helper()
	path := filepath.Join(t.TempDir(), "tokens.db")
	store, err := auth.Open(path)
	if err != nil {
		t.Fatalf("auth.Open: %v", err)
	}
	t.Cleanup(func() { _ = store.Close() })

	plain := ""
	if scopes != nil {
		plain, _, err = store.Create("test-token", scopes, "ADMIN")
		if err != nil {
			t.Fatalf("Create: %v", err)
		}
	}

	s := &mcpServer{
		jobsBase:    "http://127.0.0.1:9",
		catalogBase: "http://127.0.0.1:9",
		basicAuth:   "Basic dGVzdDp0ZXN0",
		client:      &http.Client{Timeout: 50 * time.Millisecond},
		knowledge:   &knowledgeStore{},
		authStore:   store,
	}
	return &testMCPServer{mcpServer: s, testBearer: "Bearer " + plain}
}
