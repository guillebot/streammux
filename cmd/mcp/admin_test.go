package main

import (
	"net/http"
	"testing"
)

func TestAdminRequestOKRequiresSharedSecret(t *testing.T) {
	s := &mcpServer{adminToken: "test-admin-secret"}

	req, _ := http.NewRequest(http.MethodGet, "/admin/tokens", nil)
	if s.adminRequestOK(req) {
		t.Fatal("missing header should be rejected")
	}

	req.Header.Set(adminTokenHeader, "1")
	if s.adminRequestOK(req) {
		t.Fatal("legacy X-Streammux-Mcp-Admin: 1 style value must be rejected")
	}

	req.Header.Set(adminTokenHeader, "wrong-secret")
	if s.adminRequestOK(req) {
		t.Fatal("wrong secret should be rejected")
	}

	req.Header.Set("X-Streammux-Mcp-Admin", "1")
	if s.adminRequestOK(req) {
		t.Fatal("old spoofable header alone must not authorize")
	}

	req.Header.Del("X-Streammux-Mcp-Admin")
	req.Header.Set(adminTokenHeader, "test-admin-secret")
	if !s.adminRequestOK(req) {
		t.Fatal("matching shared secret should be accepted")
	}
}

func TestAdminRequestOKFailClosedWhenUnset(t *testing.T) {
	s := &mcpServer{adminToken: ""}
	req, _ := http.NewRequest(http.MethodGet, "/admin/tokens", nil)
	req.Header.Set(adminTokenHeader, "anything")
	if s.adminRequestOK(req) {
		t.Fatal("empty MCP_ADMIN_TOKEN must reject all admin requests")
	}
}
