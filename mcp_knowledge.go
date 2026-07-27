// Package streammux embeds documentation and OpenAPI for the MCP server.
package streammux

import "embed"

// KnowledgeFS holds docs/ and docs/openapi.json for MCP knowledge tools.
//
//go:embed docs
var KnowledgeFS embed.FS
