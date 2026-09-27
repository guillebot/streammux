# Usage

Streammux is **100% API-managed**: create, update, pause, and retire jobs through HTTP; the web UI and catalog are clients of the same APIs. For the full reference — OpenAPI snapshot, authentication, every endpoint, and curl examples — see **[api.md](api.md)**.

## Base URL

By default the management API listens on port **8080** inside the container. With Compose, the host port is `JOB_MANAGEMENT_API_PORT` (default `8080`).

Example: `http://localhost:8080`

## Quick endpoint index

| Area | Base path | Details |
| ---- | --------- | ------- |
| Jobs | `/jobs` | CRUD, pause/resume/restart, status, lease, events |
| Metadata | `/jobs/meta` | Kafka topics, platform health, settings |
| Catalog | `/catalog` | Template library and push-to-live (via web UI proxy locally) |
| OpenAPI | `/swagger-ui/index.html`, `/v3/api-docs` | Interactive docs; repo snapshot in [openapi.json](openapi.json) |

See [api.md](api.md) for the complete table, schemas, and examples.

## Helper scripts

From the repository root (with `.env` optional):

| Script | Action |
| ------ | ------ |
| [create-job.sh](../create-job.sh) | `POST` sample `ROUTE_APP` job (`route-poc-1`) |
| [create-json-enricher-job.sh](../create-json-enricher-job.sh) | `POST` sample `JSON_ENRICHER` job (`json-enricher-csg-osp-1`) |
| [list-jobs.sh](../list-jobs.sh) | `GET /jobs` |
| [remove-job.sh](../remove-job.sh) | `DELETE /jobs/{JOB_ID}` (default `route-poc-1`) |

Example:

```bash
./create-job.sh
JOB_ID=route-poc-1 ./remove-job.sh
```

Ensure topics used in the sample job satisfy your `STREAMMUX_ALLOWED_*` rules, or validation will reject the create.

## First `JSON_ENRICHER` job verification

Step-by-step operator path (topics, CEL recipes, MCP helpers, counts-only hit checks): **[enricher-guide.md](enricher-guide.md)**.

Use synthetic records only. Do not copy Restricted customer/subscriber payloads into terminals, tickets, screenshots, or documentation.

1. **Check prerequisites before create.** Confirm the lookup topic has stable normalized string keys, JSON values, and compaction; confirm input/lookup read ACLs, output write ACLs, Kafka Streams group/internal-topic permissions for `streammux-{jobId}`, output retention/classification, and enough local disk/network capacity to restore the complete lookup table.
2. **Validate the definition.** Submit the JSON to `POST /jobs/validate` (or MCP `validate_job`) first. `inputTopic` and `lookupTopic` must pass input allowlists; `outputTopic` must pass output allowlists. CEL is syntax-checked here. MCP `build_enricher_job` returns a PAUSED definition; `normalize_key_preview` checks presets on synthetic strings only.
3. **Deploy the release to one orchestrator host first.** Follow [deployment.md](deployment.md#json_enricher-rollout), confirm both Java services are healthy, and inspect the `JSON_ENRICHER` runner status before expanding the deployment.
4. **Create the first job.** Prefer `PAUSED`, then `ACTIVE`. `create-json-enricher-job.sh` posts the CSG/custdata topic configuration as `ACTIVE` — review CEL against [the live job snapshot](enricher-guide.md#what-production-job-json-enricher-csg-osp-1-actually-runs) before using it in production. API/MCP `create_job` does **not** create a Job catalog entry; call `create_catalog_entry` (or Save via the UI) if you want it listed.
5. **Wait for table restore.** Verify `GET /jobs/{jobId}/lease` identifies the expected canary and `GET /jobs/{jobId}/status` reaches **RUNNING**. A large GlobalKTable can make a cold restore materially longer than a normal stateless restart. Site-orchestrator applies `STREAMMUX_LEASE_DURATION_FLOOR_SECONDS` (default 600) on claim/renew so a 30s job lease TTL cannot expire mid-restore; do not drop that floor for enricher jobs.
6. **Hit-rate with counts only.** On an authorized consumer, count envelopes: hit = one-element `enrichment[0].<name>`; miss = `[]`; do not print payloads. Output count is not hit count (misses are emitted). Synthetic fixtures only for hit/miss/drop proofs.
7. **Exercise the drop path** with synthetic invalid JSON or a missing join field. Debug logs should state only the drop reason (and invalid payload byte count), never the key or payload.
8. **Observe before broad rollout.** Input lag, output rate, runner start failures, lease ownership, disk, restore. **0% hits** → key-shape mismatch, table still restoring, or lease/image problems — [enricher-guide.md troubleshooting](enricher-guide.md#5-troubleshooting-0-hits).

For the sample configuration and exact envelope, see [job-types.md](job-types.md#json_enricher). Keep the input Kafka key in verification assertions: the runner preserves it and uses a separate normalized key only for table lookup.

## Health, metrics, and info

Both **job-management-api** and **site-orchestrator** expose Spring Boot Actuator on **port 8080 inside the container**:

| Endpoint | Auth | Use |
| -------- | ---- | --- |
| `/actuator/health` | None | Compose healthchecks, load balancers |
| `/actuator/info` | None | Build metadata |
| `/actuator/prometheus` | None | Prometheus / otelcol scrape (custom `streammux_*` metrics) |

All other HTTP routes on these services require HTTP Basic auth.

**Local Compose** — probe the API from the host:

```bash
curl -fsS "http://localhost:${JOB_MANAGEMENT_API_PORT:-8080}/actuator/health"
curl -fsS "http://localhost:${JOB_MANAGEMENT_API_PORT:-8080}/actuator/prometheus" | grep '^streammux_' | head
```

**Orchestrator** — no host port by default; exec into the container or rely on otelcol on kstreams hosts:

```bash
docker compose exec site-orchestrator \
  curl -fsS http://127.0.0.1:8080/actuator/prometheus | grep streammux_orchestrator
```

Production dashboards, otelcol, structured JSON logs, and verification steps: **[observability.md](observability.md)**.

## Operational tips

- After API restart, the in-memory read model is rebuilt from Kafka; expect brief inconsistency or empty views until consumption catches up.
- Multiple orchestrators: only one should hold the lease for a given job at a time; others should stand by until failover or release.
- Changing route input/output topics may require aligning allowlists and Kafka ACLs in your environment.
- A `JSON_ENRICHER` lookup miss is emitted, while an unusable input/join key is dropped. Monitor both business-level match quality and platform throughput.
