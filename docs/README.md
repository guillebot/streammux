# Streammux documentation

This folder complements the [root README](../README.md) with structured guides for operators, integrators, and anyone onboarding to the project.

## Contents

| Document | Audience | Purpose |
| -------- | -------- | ------- |
| [overview.md](overview.md) | Engineers, architects | What Streammux is, main components, how data flows through Kafka |
| [api.md](api.md) | Operators, integrators, automation | **100% API-managed** control plane, complete OpenAPI reference, curl examples |
| [deployment.md](deployment.md) | DevOps, platform | Images, Compose files, environment variables, building and publishing |
| [usage.md](usage.md) | Operators, API users | Quick API index, scripts, health endpoints (see [api.md](api.md) for full reference) |
| [STREAMMUX.confluence.md](STREAMMUX.confluence.md) | Broad / wiki | Plain-language summary suitable for an internal wiki (e.g. Confluence) |
| [confluence-publish.md](confluence-publish.md) | Who publishes the wiki | How to get this content into Confluence without committing secrets; includes `tools/publish_confluence_page.py` and `tools/create_ta_hub_landing_page.py` |

## Quick links

- **API reference:** [api.md](api.md) — endpoints, examples, [openapi.json](openapi.json) snapshot
- **Swagger UI:** `/swagger-ui/index.html` (via Traefik in production, or management API port locally)
