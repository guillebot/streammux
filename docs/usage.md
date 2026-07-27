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
| [list-jobs.sh](../list-jobs.sh) | `GET /jobs` |
| [remove-job.sh](../remove-job.sh) | `DELETE /jobs/{JOB_ID}` (default `route-poc-1`) |

Example:

```bash
./create-job.sh
JOB_ID=route-poc-1 ./remove-job.sh
```

Ensure topics used in the sample job satisfy your `STREAMMUX_ALLOWED_*` rules, or validation will reject the create.

## Health, metrics, and info

Spring Boot **Actuator** exposes (via `application.yml`):

- `/actuator/health`
- `/actuator/info`
- `/actuator/prometheus`

Use these for load balancers, Kubernetes probes, and monitoring.

## Operational tips

- After API restart, the in-memory read model is rebuilt from Kafka; expect brief inconsistency or empty views until consumption catches up.
- Multiple orchestrators: only one should hold the lease for a given job at a time; others should stand by until failover or release.
- Changing route input/output topics may require aligning allowlists and Kafka ACLs in your environment.
