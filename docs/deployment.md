# Deployment

Streammux ships as **two container images** plus a **Kafka cluster** you must provide.

## Images

| Image (default name) | Service | Purpose |
| -------------------- | ------- | ------- |
| `job-management-api` | `job-management-api` | REST control plane and Kafka-projected read model |
| `site-orchestrator` | `site-orchestrator` | Lease reconciliation and local runner lifecycle |
| `web-ui` | `web-ui` | React management console |
| `job-catalog-api` | `job-catalog-api` | Reusable job template catalog |

Images are pulled from GitLab Container Registry under `IMAGE_REPO` (see [.env.example](../.env.example)).

## Production vs OneLab Kafka

### Locked naming (do not change without operator approval)

| Category | Prefix / value |
| -------- | -------------- |
| App/control-plane topics | `net.optimum.experimental.streamlens.streammux.` |
| Job input allowlist (prod) | `net.optimum.`, `com.optimum.`, `gcp.optimum.` |
| Job output allowlist (Rednet data plane) | `net.optimum.` |
| Prod Kafka cluster | Rednet PNR (kb101–kb105 `:19092`, PLAINTEXT) |

App topics (definitions, leases, status, events, commands, catalog) are separate from job route input/output allowlists. Do **not** set the output allowlist to the app topic prefix.

| | Production | OneLab / local |
| --- | --- | --- |
| Cluster | **Rednet PNR Kafka** (kb101–kb105 `:19092`, PLAINTEXT) | techarch-kafka (`:9092`, PLAINTEXT) or localhost |
| App topic prefix | **`net.optimum.experimental.streamlens.streammux.`** (all environments) | same |
| Job input allowlist | `com.optimum.,net.optimum.,gcp.optimum.` | above + `lab.optimum.` (OneLab only) |
| Job output allowlist | `net.optimum.` | same |
| Ansible source of truth | `devops/inventory/group_vars/kafka_streams/streammux.yml` | role defaults + OneLab inventory |

Production Streammux uses **Rednet Kafka for configuration and management**. App-related topics **always** use the `net.optimum.experimental.streamlens.streammux.` prefix — do not shorten to `net.optimum.streammux.*`.

## Prerequisites

- **Kafka** reachable from every host running these containers (`KAFKA_BOOTSTRAP_SERVERS`).
- **Docker** (and optionally Docker Compose) for the layouts described here.
- For production Compose: an external Docker network named **`traefik-net`** is referenced in [docker-compose.yml](../docker-compose.yml); create it or adjust the file for your environment.

## Compose layouts

### Production-style: pull prebuilt images

File: [docker-compose.yml](../docker-compose.yml)

- Uses `image:` references with `pull_policy: always`.
- Exposes **only** `job-management-api` on the host (`JOB_MANAGEMENT_API_PORT`, default `8080`).
- **`site-orchestrator`** runs an embedded HTTP server on **port 8080 inside the container** (actuator health/metrics). It is **not** published on the host in production-style compose; otelcol scrapes it on the Docker network.

Typical variables:

- `KAFKA_BOOTSTRAP_SERVERS` (required)
- `IMAGE_REPO` / `TAG` (GitLab Container Registry; see [.env.example](../.env.example))
- Topic overrides: `STREAMMUX_TOPIC_JOB_*`
- API validation allowlists: `STREAMMUX_ALLOWED_*` (see [.env.example](../.env.example))

Start:

```bash
docker compose up -d
```

### Local development: build from source

File: [docker-compose.dev.yml](../docker-compose.dev.yml)

- Uses `build:` with `Dockerfile.api` and `Dockerfile.orchestrator`.

```bash
docker compose -f docker-compose.dev.yml up --build
```

## Environment variables

### Required

| Variable | Used by | Meaning |
| -------- | ------- | ------- |
| `KAFKA_BOOTSTRAP_SERVERS` | Both | Kafka bootstrap servers (e.g. `host:9092`) |

### Identity (orchestrator)

| Variable | Default | Meaning |
| -------- | ------- | ------- |
| `STREAMMUX_SITE_ID` | `site-a` | Site label for the orchestrator instance |
| `STREAMMUX_INSTANCE_ID` | `orchestrator-1` | Instance id within the site |

### Topic names (both services)

Override if your cluster uses namespaced topics:

- `STREAMMUX_TOPIC_JOB_DEFINITIONS`
- `STREAMMUX_TOPIC_JOB_LEASES`
- `STREAMMUX_TOPIC_JOB_STATUS`
- `STREAMMUX_TOPIC_JOB_EVENTS`
- `STREAMMUX_TOPIC_JOB_COMMANDS`

Defaults match the names in [overview.md](overview.md).

### Topic allowlists (API only)

Comma-separated lists. If **both** exact and prefix lists are empty for a category, that category is **unrestricted**.

| Variable | Purpose |
| -------- | ------- |
| `STREAMMUX_ALLOWED_INPUT_TOPICS` | Exact allowlist for route input topics |
| `STREAMMUX_ALLOWED_INPUT_TOPIC_PREFIXES` | Prefix allowlist for input topics (prod lock: `com.optimum.,net.optimum.,gcp.optimum.`) |
| `STREAMMUX_ALLOWED_OUTPUT_TOPICS` | Exact allowlist for route output topics |
| `STREAMMUX_ALLOWED_OUTPUT_TOPIC_PREFIXES` | Prefix allowlist for output topics (prod lock: `net.optimum.` — Rednet data plane) |

Copy [.env.example](../.env.example) to `.env` and edit. Helper scripts [create-job.sh](../create-job.sh), [list-jobs.sh](../list-jobs.sh), and [remove-job.sh](../remove-job.sh) source `.env` when present.

### In-app authentication (API + web-ui)

Default **off**. See [auth.md](auth.md) and [BREAK_GLASS.md](../BREAK_GLASS.md). Do not enable in production without an operator request.

| Variable | Default | Meaning |
| -------- | ------- | ------- |
| `STREAMMUX_AUTH_ENABLED` | `false` | Postgres session auth (Flyway `V1`–`V3`) |
| `STREAMMUX_FLYWAY_ENABLED` | `false` | Required when auth is on |
| `OIDC_ENABLED` | `false` | Entra OIDC |
| `LOCAL_AUTH_ENABLED` | `true` | Local form login |
| `STREAMMUX_BOOTSTRAP_ADMIN_USERNAME` | `breakglass` | Break-glass local admin |
| `STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD` | empty | If set, create/reset that user on boot; if empty, generate once and write the password file |
| `STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD_FILE` | `$TMPDIR/streammux-breakglass.credentials` | 0600 file for the generated password (never logged) |
| `STREAMMUX_BOOTSTRAP_ADMIN_FALLBACK_USERNAME` | empty | Local username if the primary name already exists as OIDC |

### Optional API consumer group

- `STREAMMUX_API_CONSUMER_GROUP` — Kafka consumer group for the API read model (default includes a random suffix per process).

### Observability (production kstreams)

| Variable | Used by | Meaning |
| -------- | ------- | ------- |
| `SPRING_PROFILES_ACTIVE` | job-management-api, site-orchestrator | Set to `prod` on kstreams for JSON logging (must be in compose `environment:`, not only `.env`) |
| `STREAMMUX_OTEL_KAFKA_USER` / `STREAMMUX_OTEL_KAFKA_PASSWORD` | otelcol sidecar | SCRAM credentials for platform metrics/logs Kafka (Ansible vault) |

Production telemetry is deployed by Ansible (`playbooks/kstreams/streammux/deploy.yml`): otelcol scrapes both Java services, ships metrics to Mimir and container stdout to Loki. Pin `streammux_image_tag` to a **`YYYYMMDD-NN` release tag** from GitLab `release:tag`. Details: [observability.md](observability.md), [DEPLOY.md](DEPLOY.md).

## Building and publishing images

Release and deploy workflow: **[DEPLOY.md](DEPLOY.md)** (manual `release:tag` → `YYYYMMDD-NN` → Ansible pin).

### GitLab CI (primary)

File: [.gitlab-ci.yml](../.gitlab-ci.yml)

On every push to a branch or merge request, CI runs tests then **`images:build`** pushes dev tags to the GitLab Container Registry:

| Service | Image path |
| ------- | ---------- |
| Job management API | `registry.gitlab.com/dmr4013905/techarchitecture/techarchitecture/streammux/job-management-api` |
| Site orchestrator | `.../site-orchestrator` |
| Web UI | `.../web-ui` |
| Job catalog API | `.../job-catalog-api` |
| MCP | `.../mcp` |

Dev tags per commit: `<short-sha>`, `<branch-slug>-<short-sha>`, `<branch-slug>`. On `main`, `:latest` is also pushed.

**Production releases** use manual **`release:tag`** on `main`, which creates git tag `YYYYMMDD-NN` and runs **`release:images`** (immutable registry tag only). Deploy via Ansible (`roles/kstreams/streammux`); set `streammux_image_tag` to that release ID.

### Manual local builds

Script: [build_and_push.sh](../build_and_push.sh)

Emergency or air-gapped builds only. Tags images with `YYYYMMDD-NN` (auto-computed or `-v`):

```bash
export IMAGE_REPO=registry.gitlab.com/dmr4013905/techarchitecture/techarchitecture/streammux
docker login registry.gitlab.com
./build_and_push.sh
./build_and_push.sh -v 20260825-01
./build_and_push.sh --no-push
```

## JVM / build stack

- **Java 21**, **Spring Boot 3.3.x**, **Maven** multi-module build (`mvn package` from repo root).

## Networking notes

- Orchestrators must reach the **same Kafka cluster** and use the **same topic names** as the API.
- Route-app jobs embed `streamProperties` (including `bootstrap.servers`); ensure those values are valid **inside** the runner environment (often align with `KAFKA_BOOTSTRAP_SERVERS`).
