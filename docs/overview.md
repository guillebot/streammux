# Streammux overview

## What it is

**Streammux** is a control plane for running stream-processing **jobs** across one or more sites. It uses **Apache Kafka** as the shared backbone: operators define desired job state through an HTTP API, and **site orchestrators** compete for **leases** so that each job runs on exactly one worker at a time (today’s behavior).

The main job type implemented today is **ROUTE_APP**: a Kafka Streams application that reads from configured input topics, applies **per-route filter expressions**, and writes matching records to output topics.

## Why it exists

- **Separation of concerns:** The API accepts *what* should run; orchestrators and runners decide *where* it runs locally, guided by leases.
- **Multi-site readiness:** Multiple orchestrator instances can participate; lease ownership picks a single active runner per job.
- **Auditability:** Job definitions, events, status, and leases are modeled as Kafka topics so the system can be reasoned about and extended consistently.

## Major components

| Component | Role |
| --------- | ---- |
| **job-management-api** | REST API: create/update/delete jobs, issue lifecycle commands, expose a read model built from Kafka |
| **site-orchestrator** | Consumes definitions and leases, claims/renews/releases leases, starts and stops local **job runners** |
| **runners/job-runner-route-app** | `JobRunner` implementation for `ROUTE_APP` (Kafka Streams topology from `routeAppConfig`) |
| **runners/job-runner-random-sampler** | `JobRunner` for `RANDOM_SAMPLER` (tests / sampling) |
| **runners/job-runner-alarms-to-ztr** | `JobRunner` for `ALARMS_TO_ZTR` (JSON alarm normalization with inline mapping templates and optional filter rules) |
| **job-contracts** | Shared models, topic names, validation, and the `JobRunner` SPI |
| **integration-tests** | Testcontainers-based tests (see module for current coverage) |

## Kafka topics (default names)

These names can be overridden with environment variables (see [deployment.md](deployment.md)):

- `job-definitions` — desired job configuration
- `job-leases` — which site/instance owns a job
- `job-status` — runtime status from orchestrators
- `job-events` — audit-style events
- `job-commands` — commands (e.g. pause, resume); see [limitations](#current-limitations) below

## End-to-end flow (summary)

1. A client **creates or updates** a job via `POST /jobs` or `PUT /jobs/{jobId}`. The API validates the payload (including allowed Kafka topics when configured) and publishes to Kafka.
2. The API **consumes** the same topics to maintain an **in-memory read model** used for `GET` endpoints.
3. Each **site-orchestrator** consumes definitions and leases, runs a reconcile loop, and **claims** an expired or missing lease for `ACTIVE` jobs it can run.
4. The orchestrator starts the appropriate **runner** (e.g. route-app). The runner reads/writes business data topics according to the job definition.

For diagrams and topic-level flows, see the [root README](../README.md) (Mermaid figures).

## Route-app filtering (short reference)

Each route has a `filterExpression`:

- **Field comparisons** use `==` or `!=` with JSON Pointer paths (`/message/type`) or dotted paths (`message.type`). Right-hand values are parsed as JSON when possible.
- If the expression is not a recognized comparison, matching falls back to **substring** search on the normalized payload text (JSON as-is; Protobuf converted to JSON first).

Full detail and examples are in the [root README](../README.md#route-app-filter-expressions).

## Current limitations

Accurate as of this documentation pass; verify against code and release notes before production decisions:

- **job-commands** are published by the API, but there is **no command consumer** in this repository yet; operational control is largely via `desiredState` and leases.
- **siteAffinity** and **priority** exist on job definitions but are **not** used by the current lease logic.
- **Read models** in both API and orchestrator are **in-memory** (restart loses local view until replayed from Kafka).
- **integration-tests** include placeholder scenarios; not all paths are covered end-to-end in CI.

See also [usage.md](usage.md) for API and observability.
