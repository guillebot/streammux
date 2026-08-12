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

Production Streammux on **kstreams1–4** exposes metrics and structured logs for otelcol scraping and Grafana.

### Architecture

```
job-management-api / site-orchestrator
  /actuator/health, /actuator/info, /actuator/prometheus  (permitAll — no Basic auth)
  site-orchestrator listens on :8080 inside the container (spring-boot-starter-web)
       |
       v
otelcol-contrib on each kstreams host (service_namespace=streammux)
  prometheus/local scrapes job-management-api:8080 and site-orchestrator:8080
  receiver_creator tails Docker stdout for streammux-* containers
       |
       +--> Kafka net.optimum.metrics.apps.otlp.json -> Mimir -> Grafana (9. Streammux)
       +--> Kafka net.optimum.logs.apps.otlp.json -> otel-pipeline-apps-logs -> Loki
```

Ansible templates: `devops/roles/kstreams/streammux/templates/otelcol-config.yaml.j2`, `docker-compose.yml.j2`, `env.j2`.

Deploy:

```bash
# Pin image in inventory/group_vars/kafka_streams/streammux.yml (GitLab 8-char SHA from CI)
ansible-playbook playbooks/kstreams/streammux/deploy.yml \
  --limit kstreams1.srv.hcvlny.alticeusa.net   # canary first
```

**Vault prerequisites** (`inventory/group_vars/kafka_streams/vault.yml`):

- `streammux_otelcol_kafka_password` — SCRAM for `techarch_monitoring_apps` on hcvlny `:9095` (same principal as other OneProject apps)
- `streammux_mcp_admin_token` — shared secret for web-ui ↔ MCP `/admin` (kstreams1 only)

Without otelcol, `/actuator/prometheus` still works **inside** each container; metrics do not reach Mimir until otelcol is deployed.

### Actuator and security

Both **job-management-api** and **site-orchestrator** use Spring Security with:

| Path | Auth |
| ---- | ---- |
| `/actuator/health`, `/actuator/info`, `/actuator/prometheus` | **None** (permitAll) |
| All other HTTP routes | HTTP Basic (`STREAMMUX_API_USERNAME` / `STREAMMUX_API_PASSWORD`) |

This matches the OneAlarm pattern: otelcol scrapes from the Docker network without credentials. Do not expose orchestrator port 8080 on the public host edge; production compose binds app ports to loopback and fronts them with nginx allowlists.

Compose healthchecks use `curl -fsS http://127.0.0.1:8080/actuator/health` for both Java services (replacing the old orchestrator `kill -0` probe).

### Structured logging (prod profile)

Set **`SPRING_PROFILES_ACTIVE=prod`** on **job-management-api** and **site-orchestrator** (Ansible writes it to `.env` **and** passes it through the compose `environment:` block so Spring Boot sees it).

| Profile | Format | Fields |
| ------- | ------ | ------ |
| `default`, `dev`, `test` | Plain text | `siteId`, `instanceId`, `jobId`, `action` in the pattern |
| `prod` | JSON (logstash-logback-encoder) | `@timestamp`, `service`, `siteId`, `instanceId`, `jobId`, `action`, `level`, `message`, stack traces |

Orchestrator code sets MDC (`jobId`, `action`) around reconcile and runner lifecycle so JSON logs correlate with `jobevents`.

Loki selector (after otelcol ships logs): `{service_namespace="streammux"}`.

### Custom Micrometer metrics (job-management-api)

Registered in `StreammuxPlatformMetrics`; refreshed on a schedule from the read model and platform health probe.

| Micrometer name | Prometheus export | Labels | Meaning |
| --------------- | ----------------- | ------ | ------- |
| `streammux.platform.kafka.up` | `streammux_platform_kafka_up` | — | 1 when API Kafka probe succeeds |
| `streammux.read_model.jobs` | `streammux_read_model_jobs` | — | Definitions in read model |
| `streammux.read_model.leases` | `streammux_read_model_leases` | — | Leases in read model |
| `streammux.read_model.statuses` | `streammux_read_model_statuses` | — | Statuses in read model |
| `streammux.jobs.configured` | `streammux_jobs_configured` | `desired_state`, `job_type` | Rollup of configured jobs |
| `streammux.jobs.runtime` | `streammux_jobs_runtime` | `state`, `health` | Rollup of runtime snapshots |
| `streammux.job.input_lag` | `streammux_job_input_lag` | `job_id`, `job_type` | Max consumer lag per job |
| `streammux.job.output_rate` | `streammux_job_output_rate` | `job_id` | Observed output rate |
| `streammux.job.lease_holder` | `streammux_job_lease_holder` | `job_id`, `site_id`, `instance_id` | 1 for current lease owner |

### Custom Micrometer metrics (site-orchestrator)

Registered in `StreammuxOrchestratorMetrics`.

| Micrometer name | Prometheus export | Labels | Meaning |
| --------------- | ----------------- | ------ | ------- |
| `streammux.orchestrator.active_runners` | `streammux_orchestrator_active_runners` | `runner_type` | Runners started on this host |
| `streammux.orchestrator.reconcile.total` | `streammux_orchestrator_reconcile_total` | — | Reconcile loop iterations (counter) |
| `streammux.orchestrator.runner.start_failures` | `streammux_orchestrator_runner_start_failures_total` | `job_id`, `runner_type` | Start/restart failures (counter) |
| `streammux.orchestrator.lease.owned` | `streammux_orchestrator_lease_owned` | — | Leases owned locally |

### Grafana dashboards (production)

Under `grafana-production/Alarm Management/9. Streammux/` (git-synced to prod Grafana):

| Dashboard | Focus |
| --------- | ----- |
| **Overview** | Fleet SLIs |
| **Jobs** | Per-job lag, rate, lease holder |
| **API** / **Site orchestrator** | Service detail |
| **Fleet status** | kstreams1–4 matrix |
| **Pipeline** | Telemetry scrape freshness |
| **Topology** | Architecture reference |
| **Logs** | Loki tail |

See that folder's `README.md` for starter alert PromQL (e.g. `streammux_platform_kafka_up == 0`, unhealthy jobs, lag thresholds, runner start failures).

### Verify on a kstreams host

After deploy (replace container name if needed):

```bash
# Prometheus scrape endpoints (expect HTTP 200, no auth)
docker exec streammux-job-management-api-1 \
  curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/actuator/prometheus

docker exec streammux-site-orchestrator-1 \
  curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/actuator/prometheus

# Sample custom metrics
docker exec streammux-job-management-api-1 \
  curl -fsS http://127.0.0.1:8080/actuator/prometheus | grep '^streammux_' | head

# JSON logging (prod profile)
docker logs streammux-job-management-api-1 2>&1 | tail -1 | python3 -m json.tool

# otelcol (when deployed)
curl -fsS http://127.0.0.1:13140/  # health on loopback
docker ps --format '{{.Names}}' | grep otelcol
```

In Mimir (after otelcol is running): `streammux_platform_kafka_up{service_namespace="streammux"}`.

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
| `SPRING_PROFILES_ACTIVE` | unset locally; `prod` on kstreams Ansible deploy | Enables JSON logback profile |
| `STREAMMUX_SITE_ID` / `STREAMMUX_INSTANCE_ID` | orchestrator identity | Populates log fields and otelcol `service_instance_id` labels |

## CI image tags

GitLab CI pushes **8-character** commit SHAs (e.g. `ca1de651`), not 7-char `git rev-parse --short` values. Pin `streammux_image_tag` in Ansible to the CI SHA from the merge request pipeline.

Branch pushes alone may not run CI when an open MR exists; build from the MR pipeline or merge to `main`.

## Related docs

- [API activity endpoints](api.md)
- [Deployment](deployment.md) — Compose, Ansible, env vars
- [Web console](web-console.md)
- [Architecture](architecture.md)
