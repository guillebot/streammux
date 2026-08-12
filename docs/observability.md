# Streammux observability

## Phase 1 (current): control-plane audit and lifecycle events

Streammux records **who did what** and **what happened to each job** through Kafka and the management API read model:

| Surface | Purpose |
| -------- | -------- |
| **`jobevents` topic** | Append-only audit log: API mutations, console sessions, orchestrator lifecycle |
| **`jobstatus` topic** | Latest runtime snapshot per job (compacted): state, health, `failureReason` |
| **`GET /activity`** | Global feed (last ~1000 events in the API read model) |
| **`GET /jobs/{id}/events`** | Per-job timeline |
| **Web UI `/logs`** | Filterable activity table with Authelia user when proxied |
| **Job detail → Events** | Timeline with API vs orchestrator source |

### User identity (OneLab)

Traefik Authelia sets `Remote-User`, `Remote-Email`, and related headers after forward-auth. The web UI nginx forwards these to the management API on `/jobs` and `/activity`. The API resolves the actor from those headers when `streammux.actor.trusted-proxy-headers-enabled` is true (default).

Console load calls `POST /activity/session`, which records a `SESSION` event with the resolved Authelia username.

### Orchestrator/runtime events

Each `site-orchestrator` publishes to `jobevents` when it:

- claims a lease (`CLAIMED`)
- starts a runner (`STARTED`)
- stops on pause or release (`STOPPED` / `RELEASED`)
- loses a lease to another site/instance (`RELEASED`)
- fails to start a runner (`FAILED`)

Runners attach Kafka Streams state listeners and populate `failureReason` on `jobstatus` when streams enter `ERROR` or startup throws.

### Limits

- Event history in the API is **in-memory** (rebuilt from Kafka on restart) and capped at **1000** entries for the global feed.
- The `jobevents` topic uses **delete** retention; older events are eventually dropped from Kafka.
- Phase 1 does **not** ship runner stdout/stderr or container logs to the UI.

---

## Phase 2 (current): platform metrics, custom job metrics, and container logs

Streammux on **kstreams** hosts ships operational telemetry via otelcol-contrib:

```
job-management-api / site-orchestrator /actuator/prometheus (permitAll on Docker network)
  -> otelcol-contrib (service_namespace=streammux)
  -> Kafka net.optimum.metrics.apps.otlp.json
  -> Mimir -> Grafana (Alarm Management / 9. Streammux)

Docker container stdout (streammux-*)
  -> otelcol receiver_creator
  -> Kafka net.optimum.logs.apps.otlp.json
  -> otel-pipeline-apps-logs -> Loki
```

Ansible: `roles/kstreams/streammux/templates/otelcol-config.yaml.j2` (deploy with `playbooks/kstreams/streammux/deploy.yml`).

Prod Java services use `SPRING_PROFILES_ACTIVE=prod` and **JSON logback** with stable fields: `siteId`, `instanceId`, `jobId`, `action` (when set in MDC).

### Custom Micrometer metrics (job-management-api)

| Metric | Labels | Meaning |
| ------ | ------ | ------- |
| `streammux.platform.kafka.up` | — | 1 when API Kafka probe succeeds |
| `streammux.read_model.jobs` | — | Definitions in read model |
| `streammux.read_model.leases` | — | Leases in read model |
| `streammux.read_model.statuses` | — | Statuses in read model |
| `streammux.jobs.configured` | `desired_state`, `job_type` | Rollup of configured jobs |
| `streammux.jobs.runtime` | `state`, `health` | Rollup of runtime snapshots |
| `streammux.job.input_lag` | `job_id`, `job_type` | Max consumer lag |
| `streammux.job.output_rate` | `job_id` | Observed output rate |
| `streammux.job.lease_holder` | `job_id`, `site_id`, `instance_id` | 1 for current lease owner |

Prometheus export uses underscores: e.g. `streammux_job_input_lag`.

### Custom Micrometer metrics (site-orchestrator)

| Metric | Labels | Meaning |
| ------ | ------ | ------- |
| `streammux.orchestrator.active_runners` | `runner_type` | Runners started on this host |
| `streammux.orchestrator.reconcile.total` | — | Reconcile loop iterations |
| `streammux.orchestrator.runner.start_failures` | `job_id`, `runner_type` | Start/restart failures |
| `streammux.orchestrator.lease.owned` | — | Leases owned locally |

### Grafana dashboards (production)

Under `grafana-production/Alarm Management/9. Streammux/`:

- **Overview** — fleet SLIs
- **Jobs** — per-job lag, rate, lease holder
- **API** / **Site orchestrator** — service detail
- **Fleet status** — kstreams1–4 matrix
- **Pipeline** — telemetry freshness
- **Topology** — architecture reference
- **Logs** — Loki tail

See that folder's `README.md` for starter alert PromQL.

---

## Phase 3 (target): OTLP logs via Kafka with job correlation

Align with the org direction for **OTLP-over-Kafka** so logs are:

1. **Correlatable** with `jobevents` / traces by `jobId` and trace context
2. **Queryable** from Grafana/Loki or a future in-app “deep logs” panel per job
3. **Independent of SSH** or per-VM `docker logs`

Until phase 3 lands, use the **Logs** page and per-job **Events** timeline for operational feedback; use Grafana **Streammux — Logs** and host/container logs for deep debugging.

## Environment variables

| Variable | Default | Meaning |
| -------- | ------- | ------- |
| `STREAMMUX_TRUSTED_PROXY_HEADERS` | `true` | Trust Authelia `Remote-*` headers for actor resolution |
| `SPRING_PROFILES_ACTIVE` | `prod` on kstreams deploy | Enables JSON logging profile |

## Related docs

- [API activity endpoints](api.md)
- [Web console](web-console.md)
- [Architecture](architecture.md)
