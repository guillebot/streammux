# Deployment

Streammux ships as **two container images** plus a **Kafka cluster** you must provide.

## Images

| Image (default name) | Service | Purpose |
| -------------------- | ------- | ------- |
| `streammux-job-management-api` | `job-management-api` | REST control plane and Kafka-projected read model |
| `streammux-site-orchestrator` | `site-orchestrator` | Lease reconciliation and local runner lifecycle |

Image names and registry namespace are controlled by Compose environment variables (see below).

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
- `DOCKERHUB_NAMESPACE` / `STREAMMUX_API_IMAGE_NAME` / `STREAMMUX_ORCH_IMAGE_NAME` / `STREAMMUX_IMAGE_TAG`
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
| `STREAMMUX_ALLOWED_INPUT_TOPIC_PREFIXES` | Prefix allowlist for input topics |
| `STREAMMUX_ALLOWED_OUTPUT_TOPICS` | Exact allowlist for route output topics |
| `STREAMMUX_ALLOWED_OUTPUT_TOPIC_PREFIXES` | Prefix allowlist for output topics |

Copy [.env.example](../.env.example) to `.env` and edit. Helper scripts [create-job.sh](../create-job.sh), [list-jobs.sh](../list-jobs.sh), and [remove-job.sh](../remove-job.sh) source `.env` when present.

### Optional API consumer group

- `STREAMMUX_API_CONSUMER_GROUP` — Kafka consumer group for the API read model (default includes a random suffix per process).

## Building and publishing images

Script: [build_and_push.sh](../build_and_push.sh)

- Builds both Dockerfiles, tags with a version from the `VERSION` file (bumped per run), and pushes to Docker Hub when not using `--no-push`.
- Set `DOCKERHUB_NAMESPACE` (or `DOCKERHUB_USERNAME`) to your registry namespace.

```bash
export DOCKERHUB_NAMESPACE=your-namespace
./build_and_push.sh           # patch bump + push
./build_and_push.sh --minor
./build_and_push.sh --no-push # build only
```

## JVM / build stack

- **Java 21**, **Spring Boot 3.3.x**, **Maven** multi-module build (`mvn package` from repo root).

## Networking notes

- Orchestrators must reach the **same Kafka cluster** and use the **same topic names** as the API.
- Route-app jobs embed `streamProperties` (including `bootstrap.servers`); ensure those values are valid **inside** the runner environment (often align with `KAFKA_BOOTSTRAP_SERVERS`).
