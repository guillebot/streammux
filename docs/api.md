# API reference

Streammux is **100% API-managed**. Every operational change — creating a job, updating routes, pausing processing, checking lease ownership, or retiring a pipeline — goes through HTTP APIs. There is no supported path to edit running workers by hand on orchestrator hosts, and the API does not call orchestrators directly. Instead, the management API validates your request, publishes the desired state to Kafka, and site orchestrators react to that shared event log.

The web console, catalog, and helper shell scripts in this repository are thin clients over the same APIs described here. If you can do it in the UI, you can automate it with `curl`, Terraform, or your CI pipeline.

## Services

| Service | Default port | Base path | OpenAPI |
| ------- | ------------ | --------- | ------- |
| **job-management-api** | `8080` | `/jobs`, `/jobs/meta`, `/actuator` | [Swagger UI](/swagger-ui/index.html), [OpenAPI JSON](/v3/api-docs), repo snapshot [openapi.json](openapi.json) |
| **job-catalog-api** | `3000` (proxied as `/catalog` in the web UI) | `/catalog` | Documented below (Express service; no Springdoc spec yet) |

**Production (OneLab):** `https://streammux.onelab.alticeusa.net` — Authelia SSO for the web UI; Swagger UI and OpenAPI JSON are on the same host (`/swagger-ui/index.html`, `/v3/api-docs`). Admin-only paths such as `/jobs` may require additional Traefik basic auth in some environments.

**Local Compose:** management API on `http://localhost:${JOB_MANAGEMENT_API_PORT:-8080}`; catalog via the web UI proxy at `http://localhost:${STREAMMUX_WEB_PORT:-8088}/catalog`.

## Authentication

**job-management-api** protects all routes except unauthenticated actuator probes:

| Path | Auth |
| ---- | ---- |
| `/actuator/health`, `/actuator/info` | None |
| All other `/jobs`, `/jobs/meta`, `/actuator/*` (including `/actuator/prometheus` when exposed) | HTTP Basic |

Credentials come from `STREAMMUX_API_USERNAME` and `STREAMMUX_API_PASSWORD` (defaults in `.env.example`: `streammux` / `change-me-now`). Example:

```bash
curl -u "$STREAMMUX_API_USERNAME:$STREAMMUX_API_PASSWORD" \
  "http://localhost:8080/jobs"
```

**job-catalog-api** has no built-in auth today; restrict it at the network or reverse-proxy layer in production.

## OpenAPI specification

The management API is documented with **springdoc-openapi**. When the API is running:

- **Interactive docs:** [Swagger UI](/swagger-ui/index.html)
- **Machine-readable spec:** [GET /v3/api-docs](/v3/api-docs)

A checked-in snapshot of the spec lives at [docs/openapi.json](openapi.json) in this repository (generated from a running `job-management-api` build). Regenerate after API or schema changes:

```bash
mvn -pl job-management-api -am package -DskipTests
# start the API with Kafka and topic env vars set, then:
curl -u "$STREAMMUX_API_USERNAME:$STREAMMUX_API_PASSWORD" \
  "http://localhost:${JOB_MANAGEMENT_API_PORT:-8080}/v3/api-docs" \
  | python3 -m json.tool > docs/openapi.json
```

### Complete endpoint list (job-management-api)

From the OpenAPI document (includes actuator entries when `springdoc.show-actuator` is enabled):

| Method | Path | Summary | Typical status |
| ------ | ---- | ------- | -------------- |
| `GET` | `/jobs` | List job definitions | `200` |
| `POST` | `/jobs` | Create job | `201` / `409` if id exists |
| `GET` | `/jobs/{jobId}` | Get one job | `200` / `404` |
| `PUT` | `/jobs/{jobId}` | Update job (version incremented server-side) | `200` / `404` |
| `DELETE` | `/jobs/{jobId}` | Delete job | `202` / `404` |
| `POST` | `/jobs/{jobId}/pause` | Pause command | `202` / `404` |
| `POST` | `/jobs/{jobId}/resume` | Resume command | `202` / `404` |
| `POST` | `/jobs/{jobId}/restart` | Restart command | `202` / `404` |
| `GET` | `/jobs/{jobId}/status` | Runtime status from Kafka read model | `200` (body empty if none yet) |
| `GET` | `/jobs/{jobId}/lease` | Current lease | `200` (body empty if none yet) |
| `GET` | `/jobs/{jobId}/events` | Audit events | `200` |
| `GET` | `/jobs/meta/kafka-topics` | Broker topics filtered by allowlists | `200` |
| `GET` | `/jobs/meta/health` | Kafka connectivity and read-model counts | `200` |
| `GET` | `/jobs/meta/settings` | Non-secret platform settings | `200` |
| `GET` | `/actuator/health` | Spring Boot health | `200` |
| `GET` | `/actuator/info` | Build info | `200` |

Request and response bodies use the **job-contracts** JSON models. Primary schemas in OpenAPI: `JobDefinition`, `JobRuntimeStatus`, `JobLease`, `JobEvent`, `RouteAppConfig`, `RandomSamplerConfig`, `AlarmsToZtrConfig`, `PlatformHealth`, `PlatformSettings`, `KafkaTopicCatalog`.

See the [root README](../README.md#route-app-filter-expressions) for `ROUTE_APP` filter expression syntax and job-type config shapes in the codebase under `job-contracts`.

## Job catalog API

The catalog stores reusable job templates in a compacted Kafka topic. The web UI proxies `/catalog` to **job-catalog-api**.

| Method | Path | Description |
| ------ | ---- | ----------- |
| `GET` | `/catalog/entries` | List entries (id, title, jobId from payload, timestamps) |
| `GET` | `/catalog/entries/{id}` | Full entry including job definition payload |
| `POST` | `/catalog/entries` | Create (`201`; body: `title`, `payload` object) |
| `PUT` | `/catalog/entries/{id}` | Update title and/or payload |
| `DELETE` | `/catalog/entries/{id}` | Delete (`204`; Kafka tombstone) |
| `POST` | `/catalog/entries/{id}/duplicate` | Clone entry (`201`) |
| `POST` | `/catalog/entries/{id}/push` | Deploy payload to job-management-api (`POST` or `PUT /jobs`) |
| `GET` | `/catalog/health` | Service health and entry count |
| `GET` | `/catalog/settings` | Non-secret catalog configuration |

Catalog **push** is the bridge from template to live job: it probes `GET /jobs/{jobId}` and calls `POST /jobs` or `PUT /jobs/{jobId}` on the management API.

## Examples

Set credentials and base URL once:

```bash
export API="http://localhost:${JOB_MANAGEMENT_API_PORT:-8080}"
export AUTH="$STREAMMUX_API_USERNAME:$STREAMMUX_API_PASSWORD"
```

### Create a ROUTE_APP job

```bash
curl -u "$AUTH" -X POST "$API/jobs" \
  -H 'Content-Type: application/json' \
  -d '{
    "jobId": "route-poc-1",
    "jobVersion": 0,
    "jobType": "ROUTE_APP",
    "desiredState": "ACTIVE",
    "priority": 1,
    "siteAffinity": "site-a",
    "leasePolicy": {
      "heartbeatIntervalSeconds": 10,
      "leaseDurationSeconds": 30,
      "claimBackoffMillis": 5000,
      "allowFailover": true
    },
    "parallelism": 1,
    "routeAppConfig": {
      "inputTopic": "net.optimum.monitoring.example.input",
      "inputFormat": "JSON",
      "outputFormat": "JSON",
      "routes": [
        {
          "routeId": "sip-alarms",
          "filterExpression": "application_name == \"SIP_TCP\"",
          "outputTopic": "lab.optimum.experimental.streamlens.streammux.output1"
        }
      ],
      "streamProperties": {
        "bootstrap.servers": "localhost:9092",
        "auto.offset.reset": "earliest"
      },
      "serdeProperties": {}
    },
    "labels": {},
    "tags": ["poc"],
    "updatedBy": "api-example"
  }'
```

Or use the repository script (loads `.env` if present): `./create-job.sh`.

### Create a RANDOM_SAMPLER job

```bash
curl -u "$AUTH" -X POST "$API/jobs" \
  -H 'Content-Type: application/json' \
  -d '{
    "jobId": "sampler-lab-1",
    "jobVersion": 0,
    "jobType": "RANDOM_SAMPLER",
    "desiredState": "ACTIVE",
    "priority": 1,
    "siteAffinity": "site-a",
    "leasePolicy": {
      "heartbeatIntervalSeconds": 10,
      "leaseDurationSeconds": 30,
      "claimBackoffMillis": 5000,
      "allowFailover": true
    },
    "parallelism": 1,
    "randomSamplerConfig": {
      "inputTopic": "net.optimum.monitoring.example.input",
      "outputTopic": "lab.optimum.experimental.streamlens.streammux.sampled",
      "rate": 0.01,
      "streamProperties": {
        "bootstrap.servers": "localhost:9092",
        "auto.offset.reset": "latest"
      }
    },
    "tags": ["sample"],
    "updatedBy": "api-example"
  }'
```

`rate` is a probability in **0–1** (`0.01` ≈ 1% of records forwarded).

### List jobs and inspect runtime state

```bash
curl -u "$AUTH" -sS "$API/jobs" | jq .

curl -u "$AUTH" -sS "$API/jobs/route-poc-1/status" | jq .
curl -u "$AUTH" -sS "$API/jobs/route-poc-1/lease" | jq .
curl -u "$AUTH" -sS "$API/jobs/route-poc-1/events" | jq .
```

### Pause, resume, and update desired state

Pause via command endpoint (publishes to `job-commands`; see [overview.md](overview.md#current-limitations) for consumer maturity):

```bash
curl -u "$AUTH" -X POST "$API/jobs/route-poc-1/pause"
```

Resume:

```bash
curl -u "$AUTH" -X POST "$API/jobs/route-poc-1/resume"
```

Update definition (fetch current job, edit JSON, then PUT — server increments `jobVersion`):

```bash
curl -u "$AUTH" -X PUT "$API/jobs/route-poc-1" \
  -H 'Content-Type: application/json' \
  -d @updated-job.json
```

Setting `"desiredState": "PAUSED"` on the definition is the primary operational lever today.

### Delete a job

```bash
curl -u "$AUTH" -X DELETE "$API/jobs/route-poc-1"
# or: JOB_ID=route-poc-1 ./remove-job.sh
```

### Authoring helpers (metadata)

```bash
curl -u "$AUTH" -sS "$API/jobs/meta/kafka-topics" | jq .
curl -u "$AUTH" -sS "$API/jobs/meta/settings" | jq .
curl -u "$AUTH" -sS "$API/jobs/meta/health" | jq .
```

### Catalog: save a template and push to the live API

Via web UI proxy (local):

```bash
CATALOG="http://localhost:${STREAMMUX_WEB_PORT:-8088}/catalog"

curl -sS -X POST "$CATALOG/entries" \
  -H 'Content-Type: application/json' \
  -d '{
    "title": "Route POC template",
    "payload": { "jobId": "route-poc-1", "jobType": "ROUTE_APP", "desiredState": "ACTIVE" }
  }'

curl -sS -X POST "$CATALOG/entries/1/push" | jq .
```

Push calls the management API with the stored payload; validation errors (for example topic allowlist violations) return `400` with a message from job-management-api.

## Validation and errors

The API validates job definitions before publishing to Kafka:

- **Topic allowlists** — when `STREAMMUX_ALLOWED_INPUT_*` or `STREAMMUX_ALLOWED_OUTPUT_*` are configured, input/output topics in job config must match.
- **Job type config** — the block matching `jobType` must be present and well-formed (`routeAppConfig`, `randomSamplerConfig`, or `alarmsToZtrConfig`).

Validation failures return **`400 Bad Request`** with a JSON body:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "routeAppConfig.inputTopic is not allowed: bad-topic"
}
```

Duplicate create returns **`409 Conflict`**. Missing jobs return **`404 Not Found`**.

## API-managed lifecycle (what happens after you call the API)

1. **POST/PUT /jobs** — API validates, assigns version/timestamps, publishes `JobDefinition` to `job-definitions` and audit events to `job-events`.
2. **Orchestrators** — consume definitions and leases; compete for lease ownership; start/stop the appropriate runner locally.
3. **GET /jobs/***, **status**, **lease**, **events** — served from the API’s Kafka-backed in-memory read model (rebuilt on restart from topic replay).

No step requires shell access to orchestrator machines. Monitoring uses the same API plus `/actuator/health` and `/actuator/prometheus` when exposed.

## Related documentation

- [usage.md](usage.md) — quick index, helper scripts, health endpoints
- [deployment.md](deployment.md) — ports, environment variables, Compose layout
- [openapi.json](openapi.json) — full OpenAPI 3 snapshot for job-management-api
