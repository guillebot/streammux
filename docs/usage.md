# Usage

## Base URL

By default the management API listens on port **8080** inside the container. With Compose, the host port is `JOB_MANAGEMENT_API_PORT` (default `8080`).

Example: `http://localhost:8080`

## REST API (jobs)

All routes are under **`/jobs`** (see `JobController` in the codebase).

| Method | Path | Description |
| ------ | ---- | ----------- |
| `POST` | `/jobs` | Create job (`201`; `409` if id exists) |
| `GET` | `/jobs` | List job definitions |
| `GET` | `/jobs/{jobId}` | Get one job |
| `PUT` | `/jobs/{jobId}` | Update job (version incremented server-side) |
| `DELETE` | `/jobs/{jobId}` | Delete job (`202`) |
| `POST` | `/jobs/{jobId}/pause` | Pause command (`202`) |
| `POST` | `/jobs/{jobId}/resume` | Resume command (`202`) |
| `POST` | `/jobs/{jobId}/restart` | Restart command (`202`) |
| `GET` | `/jobs/{jobId}/status` | Runtime status (`Optional` — empty body with `200` if none yet) |
| `GET` | `/jobs/{jobId}/lease` | Lease (`Optional` — empty body with `200` if none yet) |
| `GET` | `/jobs/{jobId}/events` | Audit events list (may be empty) |

Request and response bodies are JSON aligned with the **job-contracts** models (e.g. `JobDefinition` with `jobType`, `desiredState`, `routeAppConfig` for `ROUTE_APP`).

## OpenAPI / Swagger UI

The API includes **springdoc-openapi**. Typical locations (Springdoc defaults):

- **Swagger UI:** `/swagger-ui/index.html`
- **OpenAPI JSON:** `/v3/api-docs`

Actuator endpoints can appear in the OpenAPI listing when enabled (`springdoc.show-actuator: true`).

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
