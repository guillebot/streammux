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
- `site-orchestrator` has **no** published ports; it talks to Kafka and runs workers in-process.

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

### Optional API consumer group

- `STREAMMUX_API_CONSUMER_GROUP` — Kafka consumer group for the API read model (default includes a random suffix per process).

## Building and publishing images

### GitLab CI (primary)

File: [.gitlab-ci.yml](../.gitlab-ci.yml)

On every push to a branch, tag, or same-project merge request, CI runs `mvn test` then builds and pushes all four images to the GitLab Container Registry:

| Service | Image path |
| ------- | ---------- |
| Job management API | `registry.gitlab.com/dmr4013905/techarchitecture/techarchitecture/streammux/job-management-api` |
| Site orchestrator | `.../site-orchestrator` |
| Web UI | `.../web-ui` |
| Job catalog API | `.../job-catalog-api` |

Tags per commit: `<short-sha>`, `<branch-slug>-<short-sha>`, `<branch-slug>`. On `main` and git tags, `:latest` is also pushed.

Deploy hosts pull via Ansible (`roles/kstreams/streammux`); set `streammux_image_tag` to a commit SHA to pin a release.

### Manual semver releases

Script: [build_and_push.sh](../build_and_push.sh)

- Builds all four Dockerfiles, tags with a version from the `VERSION` file (bumped per run), and pushes semver + `:latest` tags.
- Set `IMAGE_REPO` to your GitLab registry path (defaults to `registry.gitlab.com/dmr4013905/techarchitecture/techarchitecture/streammux`).

```bash
export IMAGE_REPO=registry.gitlab.com/dmr4013905/techarchitecture/techarchitecture/streammux
docker login registry.gitlab.com
./build_and_push.sh           # patch bump + push
./build_and_push.sh --minor
./build_and_push.sh --no-push # build only
```

## JVM / build stack

- **Java 21**, **Spring Boot 3.3.x**, **Maven** multi-module build (`mvn package` from repo root).

## Networking notes

- Orchestrators must reach the **same Kafka cluster** and use the **same topic names** as the API.
- Route-app jobs embed `streamProperties` (including `bootstrap.servers`); ensure those values are valid **inside** the runner environment (often align with `KAFKA_BOOTSTRAP_SERVERS`).
