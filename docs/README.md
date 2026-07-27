# Streammux documentation

This folder complements the [root README](../README.md) with structured guides for operators, integrators, and anyone onboarding to the project.

## Contents

| Document | Audience | Purpose |
| -------- | -------- | ------- |
| [overview.md](overview.md) | Engineers, architects | What Streammux is, main components, how data flows through Kafka |
| [architecture.md](architecture.md) | Engineers | Control plane, Kafka topics, lease model, component map |
| [job-types.md](job-types.md) | Operators, integrators | `ROUTE_APP`, `RANDOM_SAMPLER`, `ALARMS_TO_ZTR` configuration |
| [api.md](api.md) | Operators, integrators, automation | **100% API-managed** control plane, complete OpenAPI reference, curl examples |
| [web-console.md](web-console.md) | Operators | Web UI pages: jobs, builder, catalog, health, settings |
| [deployment.md](deployment.md) | DevOps, platform | Images, Compose files, environment variables, building and publishing |
| [usage.md](usage.md) | Operators, API users | Quick API index, scripts, health endpoints (see [api.md](api.md) for full reference) |
| [STREAMMUX.confluence.md](STREAMMUX.confluence.md) | Broad / wiki | Plain-language summary suitable for an internal wiki (e.g. Confluence) |
| [confluence-publish.md](confluence-publish.md) | Who publishes the wiki | How to get wiki content into Confluence; includes publish scripts |

## Quick links

- **Repository:** [README](../README.md)
- **Architecture diagrams (Mermaid):** [README § Architecture](../README.md#architecture)
- **Filter expression reference:** [job-types.md § ROUTE_APP](job-types.md#route_app)
- **Web console:** [web-console.md](web-console.md)
- **API reference:** [api.md](api.md) — endpoints, examples, [openapi.json](openapi.json) snapshot
- **Swagger UI:** `/swagger-ui/index.html` (via Traefik in production, or management API port locally)
- **Wiki-oriented page:** [STREAMMUX.confluence.md](STREAMMUX.confluence.md)

## In-app documentation

The web UI **Documentation** page (`/#/docs`) renders this folder's operator-facing guides. Developer-only files (`AGENTS.md`, `AGENT-MODULE-HOWTO.md`, `INITIAL.PROMPT.md`, `confluence-publish.md`) are not included in the in-app view.
