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

## Phase 2 (next): structured application logs

- Emit **JSON SLF4J** from API, orchestrator, and runners with stable fields: `jobId`, `siteId`, `instanceId`, `actor`, `action`.
- Ship logs through existing **OTLP collectors** on kstreams hosts into the company log stack (Loki/Grafana).

---

## Phase 3 (target): OTLP logs via Kafka

Align with the org direction for **OTLP-over-Kafka** so logs are:

1. **Correlatable** with `jobevents` / traces by `jobId` and trace context
2. **Queryable** from Grafana/Loki or a future in-app “deep logs” panel per job
3. **Independent of SSH** or per-VM `docker logs`

Until phase 3 lands, use the **Logs** page and per-job **Events** timeline for operational feedback; use host/container logs only for deep debugging.

## Environment variables

| Variable | Default | Meaning |
| -------- | ------- | ------- |
| `STREAMMUX_TRUSTED_PROXY_HEADERS` | `true` | Trust Authelia `Remote-*` headers for actor resolution |

## Related docs

- [API activity endpoints](api.md)
- [Web console](web-console.md)
- [Architecture](architecture.md)
