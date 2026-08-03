package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"
)

func toolList() []map[string]any {
	str := map[string]any{"type": "string"}
	boolg := map[string]any{"type": "boolean"}
	freeObj := map[string]any{"type": "object", "additionalProperties": true}
	strArr := map[string]any{"type": "array", "items": str}
	return []map[string]any{
		tool("list_docs", "List embedded Streammux documentation pages (path + title). Start here to understand the system.", nil, nil),
		tool("get_doc", "Return full Markdown for a doc path, e.g. docs/overview.md (leading docs/ is optional).", map[string]any{"path": str}, []string{"path"}),
		tool("search_docs", "Full-text search across embedded docs; returns ranked path + snippet hits.", map[string]any{"query": str}, []string{"query"}),
		tool("get_schema", "Return an OpenAPI component schema by name (e.g. JobDefinition, JobRuntimeStatus, JobLease). Omit name to list available schema names.", map[string]any{"name": str}, nil),
		tool("get_openapi", "Return the full Streammux job-management-api OpenAPI document.", nil, nil),

		tool("list_jobs", "List all job definitions from the read model.", nil, nil),
		tool("get_job", "Get one job definition by job_id.", map[string]any{"job_id": str}, []string{"job_id"}),
		tool("get_job_status", "Get runtime status for a job.", map[string]any{"job_id": str}, []string{"job_id"}),
		tool("get_job_lease", "Get the current lease for a job.", map[string]any{"job_id": str}, []string{"job_id"}),
		tool("get_job_events", "List audit events for a job.", map[string]any{"job_id": str}, []string{"job_id"}),
		tool("list_activity", "List recent audit events across all jobs (newest first).", map[string]any{
			"limit":      map[string]any{"type": "integer"},
			"job_id":     str,
			"event_type": str,
			"actor":      str,
		}, nil),
		tool("get_health", "Platform health (Kafka + read model).", nil, nil),
		tool("get_settings", "Non-secret platform settings (topics, allowlists, site id).", nil, nil),
		tool("list_kafka_topics", "Broker topics filtered by configured input/output allowlists.", nil, nil),

		tool("create_job", "Create a job definition. Requires full job object and apply=true.", map[string]any{"job": freeObj, "apply": boolg}, []string{"job", "apply"}),
		tool("update_job", "Update a job definition. Requires job_id, job object, and apply=true.", map[string]any{"job_id": str, "job": freeObj, "apply": boolg}, []string{"job_id", "job", "apply"}),
		tool("rename_job", "Rename a job's id. Publishes the definition under new_job_id and a DELETED sentinel on job_id; runtime state (status, lease) and version history do not carry over. Requires job_id (current id), new_job_id, and apply=true.", map[string]any{"job_id": str, "new_job_id": str, "apply": boolg}, []string{"job_id", "new_job_id", "apply"}),
		tool("delete_job", "Mark a job deleted (desiredState=DELETED). Requires job_id and apply=true.", map[string]any{"job_id": str, "apply": boolg}, []string{"job_id", "apply"}),
		tool("pause_job", "Publish PAUSE command for a job. Requires job_id and apply=true.", map[string]any{"job_id": str, "apply": boolg}, []string{"job_id", "apply"}),
		tool("resume_job", "Publish RESUME command for a job. Requires job_id and apply=true.", map[string]any{"job_id": str, "apply": boolg}, []string{"job_id", "apply"}),
		tool("restart_job", "Publish RESTART command for a job. Requires job_id and apply=true.", map[string]any{"job_id": str, "apply": boolg}, []string{"job_id", "apply"}),

		tool("list_catalog_entries", "List job catalog templates.", nil, nil),
		tool("get_catalog_entry", "Get one catalog entry by id.", map[string]any{"id": str}, []string{"id"}),
		tool("create_catalog_entry", "Create a catalog entry. Requires entry object and apply=true.", map[string]any{"entry": freeObj, "apply": boolg}, []string{"entry", "apply"}),
		tool("update_catalog_entry", "Update a catalog entry. Requires id, entry object, and apply=true.", map[string]any{"id": str, "entry": freeObj, "apply": boolg}, []string{"id", "entry", "apply"}),
		tool("delete_catalog_entry", "Delete a catalog entry. Requires id and apply=true.", map[string]any{"id": str, "apply": boolg}, []string{"id", "apply"}),
		tool("duplicate_catalog_entry", "Clone a catalog entry to a new id. Requires id and apply=true.", map[string]any{"id": str, "apply": boolg}, []string{"id", "apply"}),
		tool("push_catalog_entry", "Deploy a catalog template to job-management-api. Requires id and apply=true.", map[string]any{"id": str, "apply": boolg}, []string{"id", "apply"}),

		tool("session", "Return the authenticated MCP session (always admin).", nil, nil),
		tool("token_create", "Create a new stm_ API token. Requires name, optional scopes, and apply=true. Plaintext shown once.", map[string]any{
			"name":   str,
			"scopes": strArr,
			"apply":  boolg,
		}, []string{"name", "apply"}),
		tool("token_list", "List MCP token metadata (no secrets).", nil, nil),
		tool("token_revoke", "Revoke a token by id. Requires apply=true.", map[string]any{"id": map[string]any{"type": "integer"}, "apply": boolg}, []string{"id", "apply"}),
	}
}

func (s *mcpServer) handleToolCall(ctx context.Context, p toolsCallParams, authz string) (string, error) {
	principal, err := s.authenticate(authz)
	if err != nil {
		return "", err
	}

	switch p.Name {
	case "list_docs", "get_doc", "search_docs", "get_schema", "get_openapi":
		if err := principal.RequireScope("docs"); err != nil {
			return "", err
		}
	case "session", "token_list":
		if err := principal.RequireScope("admin"); err != nil {
			return "", err
		}
	case "token_create", "token_revoke":
		if err := principal.RequireScope("admin"); err != nil {
			return "", err
		}
	default:
		if isWriteTool(p.Name) {
			if err := principal.RequireScope("write"); err != nil {
				return "", err
			}
		} else {
			if err := principal.RequireScope("read"); err != nil {
				return "", err
			}
		}
	}

	switch p.Name {
	case "list_docs":
		return toJSON(s.knowledge.listDocs())
	case "get_doc":
		path := argString(p.Arguments, "path")
		if path == "" {
			return "", errors.New("path is required")
		}
		e, ok := s.knowledge.getDoc(path)
		if !ok {
			return "", fmt.Errorf("doc not found: %s (use list_docs)", path)
		}
		return e.Content, nil
	case "search_docs":
		q := argString(p.Arguments, "query")
		if q == "" {
			return "", errors.New("query is required")
		}
		return toJSON(s.knowledge.searchDocs(q))
	case "get_schema":
		name := argString(p.Arguments, "name")
		if name == "" {
			return toJSON(s.knowledge.listSchemaNames())
		}
		body, ok := s.knowledge.getSchema(name)
		if !ok {
			return "", fmt.Errorf("schema not found: %s (use get_schema without name to list)", name)
		}
		return body, nil
	case "get_openapi":
		body, ok := s.knowledge.getOpenAPI()
		if !ok {
			return "", errors.New("openapi not embedded")
		}
		return body, nil
	case "session":
		scopes := make([]string, 0, len(principal.Scopes))
		for sc := range principal.Scopes {
			scopes = append(scopes, sc)
		}
		return toJSON(map[string]any{
			"username":       "admin",
			"role":           "ADMIN",
			"token_name":     principal.Name,
			"token_prefix":   principal.DisplayPrefix,
			"scopes":         scopes,
		})
	case "token_create":
		if err := requireApply(p.Arguments); err != nil {
			return "", err
		}
		name := argString(p.Arguments, "name")
		if name == "" {
			return "", errors.New("name is required")
		}
		scopes := argStringSlice(p.Arguments, "scopes")
		if len(scopes) == 0 {
			scopes = []string{"mcp", "docs", "read", "write", "admin"}
		}
		plain, rec, err := s.authStore.Create(name, scopes, "ADMIN")
		if err != nil {
			return "", err
		}
		return toJSON(map[string]any{
			"token":          plain,
			"id":             rec.ID,
			"display_prefix": rec.DisplayPrefix,
			"name":           rec.Name,
			"scopes":         rec.Scopes,
			"warning":        "Save the token now; it will not be shown again.",
		})
	case "token_list":
		recs, err := s.authStore.List()
		if err != nil {
			return "", err
		}
		return toJSON(recs)
	case "token_revoke":
		if err := requireApply(p.Arguments); err != nil {
			return "", err
		}
		id, err := argID(p.Arguments, "id")
		if err != nil {
			return "", err
		}
		if err := s.authStore.Revoke(id); err != nil {
			return "", err
		}
		return `{"ok":true}`, nil
	}

	// Job write tools
	switch p.Name {
	case "create_job":
		if err := requireApply(p.Arguments); err != nil {
			return "", err
		}
		job, err := argObject(p.Arguments, "job")
		if err != nil {
			return "", err
		}
		body, err := mustJSON(job)
		if err != nil {
			return "", err
		}
		out, err := s.jobsPost(ctx, "/jobs", body, mcpWriteHeaders(p.Name))
		if err != nil {
			return "", err
		}
		return prettyJSON(out), nil
	case "update_job":
		if err := requireApply(p.Arguments); err != nil {
			return "", err
		}
		jobID := argString(p.Arguments, "job_id")
		if jobID == "" {
			return "", errors.New("job_id is required")
		}
		job, err := argObject(p.Arguments, "job")
		if err != nil {
			return "", err
		}
		body, err := mustJSON(job)
		if err != nil {
			return "", err
		}
		out, err := s.jobsPut(ctx, "/jobs/"+url.PathEscape(jobID), body, mcpWriteHeaders(p.Name))
		if err != nil {
			return "", err
		}
		return prettyJSON(out), nil
	case "rename_job":
		if err := requireApply(p.Arguments); err != nil {
			return "", err
		}
		jobID := argString(p.Arguments, "job_id")
		if jobID == "" {
			return "", errors.New("job_id is required")
		}
		newJobID := argString(p.Arguments, "new_job_id")
		if newJobID == "" {
			return "", errors.New("new_job_id is required")
		}
		body, err := mustJSON(map[string]any{"newJobId": newJobID})
		if err != nil {
			return "", err
		}
		out, err := s.jobsPost(ctx, "/jobs/"+url.PathEscape(jobID)+"/rename", body, mcpWriteHeaders(p.Name))
		if err != nil {
			return "", err
		}
		return prettyJSON(out), nil
	case "delete_job":
		if err := requireApply(p.Arguments); err != nil {
			return "", err
		}
		jobID := argString(p.Arguments, "job_id")
		if jobID == "" {
			return "", errors.New("job_id is required")
		}
		out, err := s.jobsDelete(ctx, "/jobs/"+url.PathEscape(jobID), mcpWriteHeaders(p.Name))
		if err != nil {
			return "", err
		}
		return prettyJSON(out), nil
	case "pause_job", "resume_job", "restart_job":
		if err := requireApply(p.Arguments); err != nil {
			return "", err
		}
		jobID := argString(p.Arguments, "job_id")
		if jobID == "" {
			return "", errors.New("job_id is required")
		}
		action := strings.TrimSuffix(p.Name, "_job")
		out, err := s.jobsPost(ctx, "/jobs/"+url.PathEscape(jobID)+"/"+action, nil, mcpWriteHeaders(p.Name))
		if err != nil {
			return "", err
		}
		return prettyJSON(out), nil
	}

	// Catalog write tools
	switch p.Name {
	case "create_catalog_entry":
		if err := requireApply(p.Arguments); err != nil {
			return "", err
		}
		entry, err := argObject(p.Arguments, "entry")
		if err != nil {
			return "", err
		}
		body, err := mustJSON(entry)
		if err != nil {
			return "", err
		}
		out, err := s.catalogPost(ctx, "/catalog/entries", body, mcpWriteHeaders(p.Name))
		if err != nil {
			return "", err
		}
		return prettyJSON(out), nil
	case "update_catalog_entry":
		if err := requireApply(p.Arguments); err != nil {
			return "", err
		}
		id := argString(p.Arguments, "id")
		if id == "" {
			return "", errors.New("id is required")
		}
		entry, err := argObject(p.Arguments, "entry")
		if err != nil {
			return "", err
		}
		body, err := mustJSON(entry)
		if err != nil {
			return "", err
		}
		out, err := s.catalogPut(ctx, "/catalog/entries/"+url.PathEscape(id), body, mcpWriteHeaders(p.Name))
		if err != nil {
			return "", err
		}
		return prettyJSON(out), nil
	case "delete_catalog_entry":
		if err := requireApply(p.Arguments); err != nil {
			return "", err
		}
		id := argString(p.Arguments, "id")
		if id == "" {
			return "", errors.New("id is required")
		}
		out, err := s.catalogDelete(ctx, "/catalog/entries/"+url.PathEscape(id), mcpWriteHeaders(p.Name))
		if err != nil {
			return "", err
		}
		if len(out) == 0 {
			return `{"ok":true}`, nil
		}
		return prettyJSON(out), nil
	case "duplicate_catalog_entry", "push_catalog_entry":
		if err := requireApply(p.Arguments); err != nil {
			return "", err
		}
		id := argString(p.Arguments, "id")
		if id == "" {
			return "", errors.New("id is required")
		}
		suffix := "/duplicate"
		if p.Name == "push_catalog_entry" {
			suffix = "/push"
		}
		out, err := s.catalogPost(ctx, "/catalog/entries/"+url.PathEscape(id)+suffix, nil, mcpWriteHeaders(p.Name))
		if err != nil {
			return "", err
		}
		return prettyJSON(out), nil
	}

	// Read proxies
	path, err := readProxyPath(p)
	if err != nil {
		return "", err
	}
	var body []byte
	if strings.HasPrefix(path, "catalog:") {
		body, err = s.catalogGet(ctx, strings.TrimPrefix(path, "catalog:"))
	} else {
		body, err = s.jobsGet(ctx, path)
	}
	if err != nil {
		return "", err
	}
	return prettyJSON(body), nil
}

func isWriteTool(name string) bool {
	switch name {
	case "create_job", "update_job", "rename_job", "delete_job", "pause_job", "resume_job", "restart_job",
		"create_catalog_entry", "update_catalog_entry", "delete_catalog_entry",
		"duplicate_catalog_entry", "push_catalog_entry", "token_create", "token_revoke":
		return true
	default:
		return false
	}
}

func readProxyPath(p toolsCallParams) (string, error) {
	switch p.Name {
	case "list_jobs":
		return "/jobs", nil
	case "get_job":
		jobID := argString(p.Arguments, "job_id")
		if jobID == "" {
			return "", errors.New("job_id is required")
		}
		return "/jobs/" + url.PathEscape(jobID), nil
	case "get_job_status":
		jobID := argString(p.Arguments, "job_id")
		if jobID == "" {
			return "", errors.New("job_id is required")
		}
		return "/jobs/" + url.PathEscape(jobID) + "/status", nil
	case "get_job_lease":
		jobID := argString(p.Arguments, "job_id")
		if jobID == "" {
			return "", errors.New("job_id is required")
		}
		return "/jobs/" + url.PathEscape(jobID) + "/lease", nil
	case "get_job_events":
		jobID := argString(p.Arguments, "job_id")
		if jobID == "" {
			return "", errors.New("job_id is required")
		}
		return "/jobs/" + url.PathEscape(jobID) + "/events", nil
	case "list_activity":
		q := url.Values{}
		if limit := argString(p.Arguments, "limit"); limit != "" {
			q.Set("limit", limit)
		}
		if jobID := argString(p.Arguments, "job_id"); jobID != "" {
			q.Set("jobId", jobID)
		}
		if eventType := argString(p.Arguments, "event_type"); eventType != "" {
			q.Set("eventType", eventType)
		}
		if actor := argString(p.Arguments, "actor"); actor != "" {
			q.Set("actor", actor)
		}
		path := "/activity"
		if encoded := q.Encode(); encoded != "" {
			path += "?" + encoded
		}
		return path, nil
	case "get_health":
		return "/jobs/meta/health", nil
	case "get_settings":
		return "/jobs/meta/settings", nil
	case "list_kafka_topics":
		return "/jobs/meta/kafka-topics", nil
	case "list_catalog_entries":
		return "catalog:/catalog/entries", nil
	case "get_catalog_entry":
		id := argString(p.Arguments, "id")
		if id == "" {
			return "", errors.New("id is required")
		}
		return "catalog:/catalog/entries/" + url.PathEscape(id), nil
	default:
		return "", fmt.Errorf("unknown tool: %s", p.Name)
	}
}

func tool(name, description string, props map[string]any, required []string) map[string]any {
	if props == nil {
		props = map[string]any{}
	}
	if required == nil {
		required = []string{}
	}
	return map[string]any{
		"name":        name,
		"description": description,
		"inputSchema": map[string]any{
			"type":                 "object",
			"properties":           props,
			"required":             required,
			"additionalProperties": false,
		},
	}
}

func argString(args map[string]any, key string) string {
	if args == nil {
		return ""
	}
	if v, ok := args[key].(string); ok {
		return strings.TrimSpace(v)
	}
	return ""
}

func argID(args map[string]any, key string) (int64, error) {
	raw, ok := args[key]
	if !ok {
		return 0, fmt.Errorf("%s is required", key)
	}
	v, ok := asInt64(raw)
	if !ok || v <= 0 {
		return 0, fmt.Errorf("%s must be a positive integer", key)
	}
	return v, nil
}

func argObject(args map[string]any, key string) (map[string]any, error) {
	if args == nil {
		return nil, fmt.Errorf("%s is required", key)
	}
	raw, ok := args[key]
	if !ok {
		return nil, fmt.Errorf("%s is required", key)
	}
	m, ok := raw.(map[string]any)
	if !ok {
		return nil, fmt.Errorf("%s must be an object", key)
	}
	return m, nil
}

func argBool(args map[string]any, key string) (bool, bool) {
	if args == nil {
		return false, false
	}
	v, ok := args[key]
	if !ok {
		return false, false
	}
	b, ok := v.(bool)
	return b, ok
}

func requireApply(args map[string]any) error {
	apply, ok := argBool(args, "apply")
	if !ok || !apply {
		return errors.New("write tools require apply=true")
	}
	return nil
}

func argStringSlice(args map[string]any, key string) []string {
	if args == nil {
		return nil
	}
	raw, ok := args[key]
	if !ok {
		return nil
	}
	list, ok := raw.([]any)
	if !ok {
		return nil
	}
	out := make([]string, 0, len(list))
	for _, item := range list {
		s, ok := item.(string)
		if !ok {
			continue
		}
		out = append(out, strings.TrimSpace(s))
	}
	return out
}

func mustJSON(v any) ([]byte, error) {
	b, err := json.Marshal(v)
	if err != nil {
		return nil, fmt.Errorf("marshal request body: %w", err)
	}
	return b, nil
}

func mcpWriteHeaders(toolName string) map[string]string {
	return map[string]string{
		"X-MCP-Client":     "streammux-mcp",
		"X-MCP-Tool":       toolName,
		"X-MCP-Request-ID": strconv.FormatInt(time.Now().UnixNano(), 10),
	}
}

func asInt64(v any) (int64, bool) {
	switch t := v.(type) {
	case float64:
		return int64(t), true
	case int64:
		return t, true
	case int:
		return int64(t), true
	case json.Number:
		n, err := t.Int64()
		return n, err == nil
	case string:
		n, err := strconv.ParseInt(t, 10, 64)
		return n, err == nil
	default:
		return 0, false
	}
}

func toJSON(v any) (string, error) {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return "", err
	}
	return string(b), nil
}

func prettyJSON(body []byte) string {
	var pretty bytes.Buffer
	if err := json.Indent(&pretty, body, "", "  "); err == nil {
		return pretty.String()
	}
	return string(body)
}
