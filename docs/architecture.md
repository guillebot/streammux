# Architecture

Streammux is a Kafka-backed **control plane** for multi-site stream-processing jobs. It separates:

- a **control API** that accepts desired state
- a **Kafka event backbone** that distributes job state
- **site-local orchestrators** that compete for leases and run workers
- **pluggable job runners** (Kafka Streams today)

The API does not call orchestrators directly over HTTP. Both services build their current view from Kafka topics.

> **Current behavior:** each job runs on one worker at a time — the current lease owner.

## Control plane flow

1. Operators create or update jobs via `POST /jobs` or `PUT /jobs/{jobId}`.
2. job-management-api validates the payload, publishes to Kafka (`job-definitions`, `job-events`, `job-commands`), and maintains an in-memory read model from the same topics.
3. Each site-orchestrator consumes definitions and leases, runs a reconcile loop, and claims or renews leases for `ACTIVE` jobs.
4. The orchestrator starts the matching **JobRunner** (route-app, random-sampler, alarms-to-ztr, etc.) on the lease holder.
5. Runners read and write **business data topics** according to the job definition.

## Kafka control topics

Default names (override with `STREAMMUX_TOPIC_JOB_*`):

| Topic | Purpose |
| ----- | ------- |
| `job-definitions` | Desired job configuration |
| `job-leases` | Which site/instance owns a job |
| `job-status` | Runtime status from orchestrators |
| `job-events` | Audit-style events |
| `job-commands` | Pause, resume, restart commands (see [limitations](#current-limitations)) |

Additional topic:

| Topic | Purpose |
| ----- | ------- |
| `job-catalog-entries` (default) | Compacted catalog of reusable job templates (`STREAMMUX_TOPIC_JOB_CATALOG`) |

## Components

| Component | Role |
| --------- | ---- |
| **job-management-api** | REST API, Kafka publish/consume, in-memory read model, topic allowlist validation |
| **site-orchestrator** | Lease reconcile, runner lifecycle |
| **web-ui** | Operator console (React SPA behind nginx) |
| **job-catalog-api** | Catalog CRUD + push-to-API, Kafka-backed |
| **runners/** | Pluggable `JobRunner` implementations |
| **job-contracts** | Shared models, topic names, validation, SPI |

## Lease model

- `ACTIVE` jobs are eligible to run.
- If no lease exists or the lease is expired, an orchestrator may claim it.
- The lease owner renews before expiry.
- When the job is no longer `ACTIVE`, the orchestrator releases the lease.
- `siteAffinity` and `priority` exist on definitions but are **not** used by current lease logic.

## Diagrams

Full Mermaid architecture and topic-interconnection diagrams live in the [root README](../README.md#architecture).

## Current limitations

- **job-commands** are published by the API; there is **no command consumer** in this repository yet — operational control is largely via `desiredState` and leases.
- **Read models** in API, orchestrator, and catalog are **in-memory** (restart until Kafka replay).
- **integration-tests** include placeholder scenarios; not all paths are covered end-to-end in CI.

See also [overview.md](overview.md) and [job-types.md](job-types.md).
