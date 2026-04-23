# AGENTS.md — streammux

Persistent context for AI assistants and humans resuming work on this repository. **Read this at the start of a session** before making changes.

---

## What this project is

**streammux** is a Kafka-backed control plane for multi-site stream-processing jobs:

- Operators define **desired state** via HTTP; there is **no direct HTTP** from the API to orchestrators.
- **Apache Kafka** carries job definitions, leases, status, events, commands, and (optionally) catalog data.
- **site-orchestrator** instances consume definitions and leases, reconcile **leases**, and start/stop local **job runners**.
- Today’s main runner is **ROUTE_APP** (`runners/job-runner-route-app`): Kafka Streams, per-route **filter expressions** (field `==` / `!=` or substring fallback on normalized payload).

Canonical architecture and topic diagrams: [README.md](README.md). Shorter overview: [docs/overview.md](docs/overview.md).

---

## Repository layout

| Path | Role |
| ---- | ---- |
| `job-contracts` | Shared models, topic names, validation, `JobRunner` SPI (Maven) |
| `job-management-api` | Spring Boot REST + Kafka in/out + in-memory read model |
| `site-orchestrator` | Spring Boot: lease reconcile, runner lifecycle |
| `runners/job-runner-route-app` | `ROUTE_APP` Kafka Streams runner |
| `runners/job-runner-random-sampler` | Additional runner (sampling / tests) |
| `runners/job-runner-alarms-to-ztr` | `ALARMS_TO_ZTR` runner (JSON alarm normalization via inline mapping/filter) |
| `integration-tests` | Testcontainers; some scenarios still placeholder-level |
| `web-ui/` | Vite + React 19 + TypeScript management UI |
| `job-catalog-api/` | Small Node (Express + KafkaJS) service for catalog topic |
| `docs/` | overview, usage, deployment, Confluence helpers |

**Maven reactor** (Java 21): root [pom.xml](pom.xml) — does **not** include `web-ui` or `job-catalog-api`; those build via Docker or their own `npm` scripts.

---

## Tech stack (pinned at analysis time)

- **Java 21**, **Spring Boot 3.5.x**, **Kafka clients 3.9.x**, Testcontainers for ITs.
- **Web UI**: Vite 6, React 19, react-router-dom 7, TypeScript ~5.8.
- **Catalog API**: Node, Express, KafkaJS.

---

## Running and building

- **Full Java build:** `mvn package` (from repo root).
- **Docker — build from source:** `docker compose -f docker-compose.dev.yml up --build` (requires `.env` with `KAFKA_BOOTSTRAP_SERVERS`, etc.).
- **Docker — pull images:** `docker compose up` using [docker-compose.yml](docker-compose.yml) (expects external Kafka; production-style compose references external network **`traefik-net`** — create or edit compose for your host).
- **Sample job script:** `./create-job.sh` (posts a sample job to the API when it is up).
- **Web UI local dev:** `cd web-ui && npm install && npm run dev` (optional `VITE_EXAMPLE_KAFKA_BOOTSTRAP` for templates; see [.env.example](.env.example) comments).

Kafka is **never** started by this repo’s compose; you must point `KAFKA_BOOTSTRAP_SERVERS` at a reachable cluster.

---

## Environment and secrets

- Copy [.env.example](.env.example) to `.env` and adjust. **Do not commit real credentials or internal hostnames** if the repo is shared publicly.
- Common variables: `KAFKA_BOOTSTRAP_SERVERS`, `JOB_MANAGEMENT_API_PORT`, `STREAMMUX_SITE_ID`, `STREAMMUX_INSTANCE_ID`, topic overrides `STREAMMUX_TOPIC_JOB_*`, allowlists `STREAMMUX_ALLOWED_INPUT_*` / `STREAMMUX_ALLOWED_OUTPUT_*`, API basic auth `STREAMMUX_API_USERNAME` / `STREAMMUX_API_PASSWORD`, `STREAMMUX_WEB_PORT` for the UI.

---

## Known limitations (verify in code before relying on them)

Summarized from [README.md](README.md) and [docs/overview.md](docs/overview.md):

- **`job-commands`** is written by the API; there is **no command consumer** in-repo yet — lifecycle is mostly **desiredState** + leases.
- **`siteAffinity`** and **`priority`** on job definitions exist but **lease logic does not use them** yet.
- **Read models** are in-memory (restart until Kafka replay).
- Some **integration-tests** are placeholders, not full E2E.

---

## Documentation map

| Doc | Use |
| --- | --- |
| [README.md](README.md) | Architecture, route-app filter syntax, local dev |
| [docs/overview.md](docs/overview.md) | Component table, limitations |
| [docs/usage.md](docs/usage.md) | API / observability |
| [docs/deployment.md](docs/deployment.md) | Images, compose, env vars (note: may lag slightly vs compose if new services were added) |

---

## Owner / collaborator guidelines for agents

These preferences come from the project owner’s Cursor rules; **follow them in this repo**.

### Execution and environment

- This is a **real** environment: **run commands** (build, tests, grep, compose) yourself; do not only paste instructions for the user unless they must perform an irreversible human step.
- On failures: **diagnose, retry, or try an alternative** rather than stopping after one error.
- When the user or system provides **“Today’s date”** in session metadata, treat that year as authoritative (e.g. **2026** for searches and “current” wording).

### Code and scope

- **Minimal, task-focused diffs.** No drive-by refactors, unrelated files, or scope creep. Match existing naming, types, imports, and comment density.
- **Do not remove** unrelated comments or code. Prefer one clear code path over many special cases.
- Avoid unsolicited new markdown files unless the user asked (this file was requested as the project’s agent handoff).

### Communication

- **Clear, complete sentences**; concise but not telegraphic. Proportional length to task complexity.
- Use **markdown links** with full URLs for web references; full paths for files when citing.
- When showing **existing** code from this repo, use **code citations** (Cursor): after the opening fence, the first line is only `startLine:endLine:relative/path` (no `tsx`/`java` language tag). The opening fence backticks must be on their own line, not prefixed by `-` or other text. Then the cited lines, then the closing fence.
- Inside citations, literal code only (no HTML entities for `<`, `>`, `&`).
- Use **bold** and inline backticks sparingly (not for decoration).
- Avoid “§” in user-facing text. Skip engagement-bait closings.

### Conversation and intent

- Interpret each message in **light of prior turns**; mid-thread messages are usually **steering**, not canceling, unless clearly stated.
- Infer **underlying goals** and constraints from the arc of the chat, not only the last sentence.

### Skills and MCP

- When a task matches a **Cursor skill** (e.g. babysit PRs, canvas, hooks, rules, settings), **read the skill file** under `~/.cursor/skills-cursor/<name>/SKILL.md` and follow it — do not only name it.
- Use **MCP tools** when they clearly fit (ticketing, observability, etc.).

### Canvas / heavy artifacts

- For large analytical deliverables (tables, audits, billing, multi-step tool output meant as a **standalone artifact**), prefer a **Cursor Canvas** (`.canvas.tsx`) per the canvas skill — not a giant markdown dump.

---

## Quick checklist for a new session

1. Read this file + skim [README.md](README.md) if touching Kafka/orchestration.
2. Confirm `.env` / cluster access for anything that talks to Kafka.
3. Run targeted tests after Java changes: e.g. `mvn -pl job-management-api test` or full `mvn package` as appropriate.
4. Respect **topic allowlists** when creating jobs in a configured environment.

---

*Last updated: project analysis and guidelines snapshot (maintain this file when architecture or workflows materially change).*
