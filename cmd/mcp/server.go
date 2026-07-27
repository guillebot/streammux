package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	sdkmcp "github.com/modelcontextprotocol/go-sdk/mcp"
)

func (s *mcpServer) buildSDKServer(authz string) *sdkmcp.Server {
	srv := sdkmcp.NewServer(
		&sdkmcp.Implementation{Name: "streammux-mcp", Version: serverVersion},
		&sdkmcp.ServerOptions{Instructions: serverInstructions},
	)
	s.registerSDKTools(srv, authz)
	s.registerSDKResources(srv, authz)
	return srv
}

func (s *mcpServer) streamableMCPHandler() http.Handler {
	return sdkmcp.NewStreamableHTTPHandler(func(r *http.Request) *sdkmcp.Server {
		return s.buildSDKServer(r.Header.Get("Authorization"))
	}, &sdkmcp.StreamableHTTPOptions{Stateless: true})
}

func (s *mcpServer) registerSDKTools(srv *sdkmcp.Server, authz string) {
	for _, spec := range toolList() {
		name, _ := spec["name"].(string)
		desc, _ := spec["description"].(string)
		schema, err := json.Marshal(spec["inputSchema"])
		if err != nil {
			continue
		}
		toolName := name
		srv.AddTool(&sdkmcp.Tool{
			Name:        name,
			Description: desc,
			InputSchema: json.RawMessage(schema),
		}, func(ctx context.Context, req *sdkmcp.CallToolRequest) (*sdkmcp.CallToolResult, error) {
			var args map[string]any
			if len(req.Params.Arguments) > 0 {
				if err := json.Unmarshal(req.Params.Arguments, &args); err != nil {
					return toolError(fmt.Errorf("invalid arguments: %w", err)), nil
				}
			}
			out, err := s.handleToolCall(ctx, toolsCallParams{Name: toolName, Arguments: args}, authz)
			if err != nil {
				return toolError(err), nil
			}
			return &sdkmcp.CallToolResult{
				Content: []sdkmcp.Content{&sdkmcp.TextContent{Text: out}},
			}, nil
		})
	}
}

func (s *mcpServer) registerSDKResources(srv *sdkmcp.Server, authz string) {
	for _, meta := range s.knowledge.resourceList() {
		uri, _ := meta["uri"].(string)
		name, _ := meta["name"].(string)
		desc, _ := meta["description"].(string)
		mime, _ := meta["mimeType"].(string)
		resourceURI := uri
		srv.AddResource(&sdkmcp.Resource{
			URI:         uri,
			Name:        name,
			Description: desc,
			MIMEType:    mime,
		}, func(ctx context.Context, _ *sdkmcp.ReadResourceRequest) (*sdkmcp.ReadResourceResult, error) {
			if _, err := s.authenticate(authz); err != nil {
				return nil, err
			}
			e, ok := s.knowledge.resourceByURI(resourceURI)
			if !ok {
				return nil, fmt.Errorf("resource not found: %s", resourceURI)
			}
			return &sdkmcp.ReadResourceResult{
				Contents: []*sdkmcp.ResourceContents{{
					URI:      resourceURI,
					MIMEType: e.MIME,
					Text:     e.Content,
				}},
			}, nil
		})
	}
}

func toolError(err error) *sdkmcp.CallToolResult {
	return &sdkmcp.CallToolResult{
		Content: []sdkmcp.Content{&sdkmcp.TextContent{Text: err.Error()}},
		IsError: true,
	}
}
