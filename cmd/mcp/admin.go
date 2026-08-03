package main

import (
	"crypto/subtle"
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"strings"
)

// adminTokenHeader is injected by the web-ui nginx (and local Vite proxy) from
// MCP_ADMIN_TOKEN. The literal value "1" is never accepted — callers must present
// the shared secret. Direct access to :8090 with a spoofed header must fail.
const adminTokenHeader = "X-Streammux-Mcp-Admin-Token"

func (s *mcpServer) registerAdmin(mux *http.ServeMux) {
	mux.HandleFunc("/admin/tokens", s.adminTokensHandler)
	mux.HandleFunc("/admin/tokens/", s.adminTokenRevokeHandler)
}

func (s *mcpServer) adminRequestOK(r *http.Request) bool {
	want := strings.TrimSpace(s.adminToken)
	if want == "" {
		return false
	}
	got := strings.TrimSpace(r.Header.Get(adminTokenHeader))
	if got == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1
}

func (s *mcpServer) adminTokensHandler(w http.ResponseWriter, r *http.Request) {
	if !s.adminRequestOK(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	switch r.Method {
	case http.MethodGet:
		recs, err := s.authStore.List()
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		writeJSON(w, recs)
	case http.MethodPost:
		var body struct {
			Name   string   `json:"name"`
			Scopes []string `json:"scopes"`
			Role   string   `json:"role"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&body); err != nil {
			http.Error(w, "invalid json", http.StatusBadRequest)
			return
		}
		if strings.TrimSpace(body.Name) == "" {
			http.Error(w, "name is required", http.StatusBadRequest)
			return
		}
		scopes := body.Scopes
		if len(scopes) == 0 {
			scopes = []string{"mcp", "docs", "read", "write", "admin"}
		}
		role := body.Role
		if role == "" {
			role = "ADMIN"
		}
		plain, rec, err := s.authStore.Create(body.Name, scopes, role)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		writeJSON(w, map[string]any{
			"token":          plain,
			"id":             rec.ID,
			"display_prefix": rec.DisplayPrefix,
			"name":           rec.Name,
			"scopes":         rec.Scopes,
			"role":           rec.Role,
			"created_at":     rec.CreatedAt,
			"warning":        "Save the token now; it will not be shown again.",
		})
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *mcpServer) adminTokenRevokeHandler(w http.ResponseWriter, r *http.Request) {
	if !s.adminRequestOK(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	if r.Method != http.MethodDelete {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	idStr := strings.TrimPrefix(r.URL.Path, "/admin/tokens/")
	idStr = strings.Trim(idStr, "/")
	id, err := strconv.ParseInt(idStr, 10, 64)
	if err != nil || id <= 0 {
		http.Error(w, "invalid token id", http.StatusBadRequest)
		return
	}
	if err := s.authStore.Revoke(id); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	writeJSON(w, map[string]bool{"ok": true})
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	_ = enc.Encode(v)
}
