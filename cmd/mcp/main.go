package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"gitlab.com/dmr4013905/techarchitecture/techarchitecture/streammux/internal/auth"
)

const serverVersion = "0.1.0"

const serverInstructions = `Streammux is a Kafka-backed control plane for multi-site stream-processing jobs. ` +
	`Operators define desired state via HTTP; Apache Kafka carries job definitions, leases, status, events, and commands. ` +
	`Site orchestrators reconcile leases and run job runners (ROUTE_APP, RANDOM_SAMPLER, ALARMS_TO_ZTR).

This MCP server exposes embedded documentation and proxies the job-management-api and job-catalog-api. ` +
	`Authentication uses Bearer stm_ tokens (create with stmctl token create). Every caller is treated as admin.

Start here:
1. list_docs then get_doc(docs/overview.md) and get_doc(docs/job-types.md)
2. get_schema(JobDefinition) for the job JSON shape
3. list_jobs / get_job to inspect live jobs; get_job_status and get_job_lease for runtime state
4. list_catalog_entries for reusable job templates; push_catalog_entry deploys a template to the cluster

Tool tiers (Bearer stm_ token scopes):
- docs: list_docs, get_doc, search_docs, get_schema, get_openapi
- read: list_jobs, get_job, get_job_status, get_job_lease, get_job_events, get_health, get_settings, list_kafka_topics, catalog reads
- write: create_job, update_job, delete_job, pause_job, resume_job, restart_job, catalog mutations (apply=true)
- admin: token_create, token_list, token_revoke (apply=true for create/revoke)

All mutating tools require apply=true. Resources are available under the streammux:// URI scheme.`

type toolsCallParams struct {
	Name      string         `json:"name"`
	Arguments map[string]any `json:"arguments"`
}

type mcpServer struct {
	jobsBase    string
	catalogBase string
	basicAuth   string
	adminToken  string
	client      *http.Client
	knowledge   *knowledgeStore
	authStore   *auth.Store
}

func main() {
	addr := env("MCP_HTTP_ADDR", ":8090")
	tokenDB := env("MCP_TOKEN_DB_PATH", "/data/tokens.db")
	if err := os.MkdirAll(filepath.Dir(tokenDB), 0o755); err != nil {
		log.Fatal(err)
	}
	authStore, err := auth.Open(tokenDB)
	if err != nil {
		log.Fatal(err)
	}
	defer authStore.Close()

	user := env("STREAMMUX_API_USERNAME", "streammux")
	pass := env("STREAMMUX_API_PASSWORD", "change-me-now")
	basic := "Basic " + base64.StdEncoding.EncodeToString([]byte(user+":"+pass))
	adminToken := strings.TrimSpace(os.Getenv("MCP_ADMIN_TOKEN"))
	if adminToken == "" {
		log.Fatal("MCP_ADMIN_TOKEN is required (shared secret for web-ui → /admin token management)")
	}
	if len(adminToken) < 24 {
		log.Fatal("MCP_ADMIN_TOKEN must be at least 24 characters (openssl rand -hex 32)")
	}

	s := &mcpServer{
		jobsBase:    strings.TrimRight(env("JOB_MANAGEMENT_API_URL", "http://job-management-api:8080"), "/"),
		catalogBase: strings.TrimRight(env("JOB_CATALOG_API_URL", "http://job-catalog-api:3000"), "/"),
		basicAuth:   basic,
		adminToken:  adminToken,
		client:      &http.Client{Timeout: 30 * time.Second},
		knowledge:   loadKnowledge(),
		authStore:   authStore,
	}
	log.Printf("streammux-mcp knowledge: %d docs, %d openapi schemas", len(s.knowledge.docs), len(s.knowledge.schemaDefs))

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("ok"))
	})
	mux.Handle("/mcp", s.streamableMCPHandler())
	s.registerAdmin(mux)
	log.Printf("streammux-mcp listening on %s (streamable HTTP, stateless; /admin gated by MCP_ADMIN_TOKEN)", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatal(err)
	}
}

func (s *mcpServer) authenticate(authz string) (*auth.Principal, error) {
	p, err := s.authStore.Validate(authz)
	if err != nil {
		if errors.Is(err, auth.ErrUnauthorized) {
			return nil, fmt.Errorf("missing or invalid bearer token")
		}
		return nil, err
	}
	return p, nil
}

func (s *mcpServer) jobsGet(ctx context.Context, path string) ([]byte, error) {
	return s.apiRequest(ctx, http.MethodGet, s.jobsBase+path, nil, nil)
}

func (s *mcpServer) jobsPost(ctx context.Context, path string, body []byte, extra map[string]string) ([]byte, error) {
	return s.apiRequest(ctx, http.MethodPost, s.jobsBase+path, body, extra)
}

func (s *mcpServer) jobsPut(ctx context.Context, path string, body []byte, extra map[string]string) ([]byte, error) {
	return s.apiRequest(ctx, http.MethodPut, s.jobsBase+path, body, extra)
}

func (s *mcpServer) jobsDelete(ctx context.Context, path string, extra map[string]string) ([]byte, error) {
	return s.apiRequest(ctx, http.MethodDelete, s.jobsBase+path, nil, extra)
}

func (s *mcpServer) catalogGet(ctx context.Context, path string) ([]byte, error) {
	return s.apiRequest(ctx, http.MethodGet, s.catalogBase+path, nil, nil)
}

func (s *mcpServer) catalogPost(ctx context.Context, path string, body []byte, extra map[string]string) ([]byte, error) {
	return s.apiRequest(ctx, http.MethodPost, s.catalogBase+path, body, extra)
}

func (s *mcpServer) catalogPut(ctx context.Context, path string, body []byte, extra map[string]string) ([]byte, error) {
	return s.apiRequest(ctx, http.MethodPut, s.catalogBase+path, body, extra)
}

func (s *mcpServer) catalogDelete(ctx context.Context, path string, extra map[string]string) ([]byte, error) {
	return s.apiRequest(ctx, http.MethodDelete, s.catalogBase+path, nil, extra)
}

func (s *mcpServer) apiRequest(ctx context.Context, method, url string, body []byte, extraHeaders map[string]string) ([]byte, error) {
	var bodyReader io.Reader
	if len(body) > 0 {
		bodyReader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, url, bodyReader)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	if strings.HasPrefix(url, s.jobsBase) {
		req.Header.Set("Authorization", s.basicAuth)
	}
	if len(body) > 0 {
		req.Header.Set("Content-Type", "application/json")
	}
	for k, v := range extraHeaders {
		if strings.TrimSpace(k) != "" && strings.TrimSpace(v) != "" {
			req.Header.Set(k, v)
		}
	}
	res, err := s.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(res.Body, 4<<20))
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		return nil, fmt.Errorf("upstream %s %s failed (%d): %s", method, url, res.StatusCode, strings.TrimSpace(string(b)))
	}
	return b, nil
}

func env(key, fallback string) string {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return fallback
	}
	return v
}
