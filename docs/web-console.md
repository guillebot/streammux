# Web console

The **web UI** (`web-ui`) is a React management console served on port **8088** by default (`STREAMMUX_WEB_PORT`). It proxies API calls to **job-management-api** (`/jobs`) and **job-catalog-api** (`/catalog`) through nginx inside the container.

Production deployments (for example OneLab) typically expose the UI at `streammux.onelab.alticeusa.net` with Authelia SSO. Swagger UI and raw OpenAPI docs are routed to the management API on the same host (`/swagger-ui`, `/v3/api-docs`).

## Navigation

| Page | Route | Purpose |
| ---- | ----- | ------- |
| Job management | `/` | List live jobs from the Kafka-backed read model |
| Job catalog | `/catalog` | Browse reusable job definition templates |
| Job Builder | `/job/builder` | Visual wizard for common job types |
| Health | `/health` | Platform and catalog health snapshots |
| Settings | `/settings` | Non-secret runtime configuration |
| Documentation | `/docs` | In-app guides (this documentation set) |

Routes use **hash-based** URLs (`/#/jobs`, `/#/docs/overview`, etc.) so the static nginx host can serve the SPA without server-side routing rules.

## Job management

The home page lists all jobs returned by `GET /jobs`. Each row links to the job detail view.

**Actions:**

- **Job builder** — opens the visual builder for `ROUTE_APP` or `RANDOM_SAMPLER` jobs.
- **New job** — opens a blank job editor (`/job/new`) where you can paste or edit the full JSON definition.

The job detail page (`/job/:jobId`) shows definition, status, lease, and events. Use it to create, update, pause, resume, restart, or delete jobs. Validation errors from the API (for example topic allowlist violations) appear inline.

## Job Builder

The builder helps operators create jobs without writing JSON from scratch.

**Supported job types today:** `ROUTE_APP`, `RANDOM_SAMPLER`.

1. Enter a **job ID** and select the **job type**.
2. Pick **input** and **output** topics from dropdowns populated by `GET /jobs/meta/kafka-topics` (broker topics filtered by configured allowlists). If the catalog call fails, fallback topic lists from environment templates are used.
3. For `ROUTE_APP`, set a single route with one filter expression and output topic.
4. For `RANDOM_SAMPLER`, set a **sample percent** (0–100). The API stores `randomSamplerConfig.rate` as a fraction (for example `25` → `0.25`).
5. Click **Continue to editor** to open the full job JSON pre-filled on the New job page. Review and submit from there.

`ALARMS_TO_ZTR` jobs are not in the builder yet; create them via the job editor, catalog, or API. See [job-types.md](job-types.md).

## Job catalog

The catalog stores **reusable job definition templates** in a compacted Kafka topic (`STREAMMUX_TOPIC_JOB_CATALOG`, default `job-catalog-entries`). The **job-catalog-api** service maintains an in-memory projection and exposes REST endpoints under `/catalog`.

**Typical workflow:**

1. **New catalog entry** — save a job definition JSON with a human-readable title.
2. **Edit** — update title or payload.
3. **Duplicate** — clone an entry for a variant.
4. **Push** — deploy the payload to job-management-api (`POST /jobs` if the job id is new, otherwise `PUT /jobs/{jobId}`).

The catalog list shows id, title, embedded `jobId` from the JSON payload, and last updated time.

## Health

The Health page aggregates:

- **Platform health** (`GET /jobs/meta/health`) — Kafka connectivity, configured control-plane topic presence, in-memory read-model counts.
- **Catalog health** (`GET /catalog/health`) — catalog API status and entry count.

Refresh reloads both panels. Overall status is the worst of the two component statuses (`UP`, `DEGRADED`, `DOWN`).

## Settings

Settings displays non-secret configuration snapshots:

- **job-management-api** (`GET /jobs/meta/settings`) — application name, bootstrap servers, consumer group, Streammux topic names, topic validation allowlists, actuator exposure.
- **job-catalog-api** (`GET /catalog/settings`) — Kafka topic, client id, upstream job-management-api URL, topic auto-create settings.

Secrets (API basic-auth passwords, etc.) are never shown.

**External API docs:** [Swagger UI](/swagger-ui/index.html) (opens in a new tab). See also the in-app [API reference](/#/docs/api). On local dev without Traefik, Swagger is available on the management API port directly (`http://localhost:8080/swagger-ui/index.html`), not through the web UI port.

## Local development

```bash
cd web-ui
npm install
npm run dev
```

Vite dev server proxies `/jobs` and `/catalog` to local backends. Optional `VITE_EXAMPLE_KAFKA_BOOTSTRAP` seeds builder templates when the topic catalog endpoint is unavailable.
