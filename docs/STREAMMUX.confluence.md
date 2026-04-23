# Streammux — internal overview

*This page is written for a broad audience: product, operations, and engineering leads. Technical detail lives in the repository under the `docs/` folder.*

---

## What is Streammux?

**Streammux** is software that helps teams run **streaming data jobs** in a controlled, repeatable way. Instead of logging into servers to start or stop stream processors by hand, operators define **jobs** through a simple **web API**. Streammux coordinates those jobs using **Apache Kafka** as a shared message backbone.

In practical terms, Streammux today focuses on **routing** data between Kafka topics: it can read records from an input topic, apply rules to decide which records matter, and send matching records to one or more output topics. Only **one** active copy of a given job runs at a time, which avoids duplicate processing when multiple data centers or servers could otherwise try to run the same work.

---

## Problems it helps solve

- **Consistency:** Desired configuration lives in one place (the API and Kafka topics), not only in tribal knowledge or manual commands.
- **Multi-site operations:** The design supports more than one deployment location; a **lease** mechanism decides which site runs a job at any moment.
- **Safety guardrails:** The API can restrict which Kafka topics jobs are allowed to read from or write to, reducing the risk of misconfigured pipelines touching the wrong data.

---

## How Streammux relates to Kafka Streams and Apache Flink

**Kafka Streams** is a library: you embed it in your own service and ship that service. There is no built-in “control plane” for many teams to register jobs, enforce topic policy, or coordinate who runs what across sites—you design and operate all of that yourself.

**Apache Flink** is a full **stream processing cluster** and programming model. It shines when you need substantial **stateful** computation: event-time semantics, large or complex **aggregations**, **joins** across streams, **windows**, savepoints, and mature operational patterns for heavy analytics or ETL-at-scale.

**Streammux** sits **in between**. Today’s main worker is still **Kafka Streams** under the hood, but Streammux adds a **Kafka-backed control plane** (definitions, leases, status), an **operator-facing API**, and **guardrails** such as topic allowlists. You get something closer to “managed jobs” than a one-off Streams binary, without taking on Flink’s operational and conceptual weight when the job is really **routing, filtering, and fan-out** between topics.

### Why choose Streammux over Flink for some jobs?

Flink is often **more than you need** when the problem is “read from Kafka, apply straightforward rules, write to Kafka,” especially if you do **not** need advanced state, complex time semantics, or Flink-specific APIs. Streammux can be a **lighter** path for those cases: fewer moving parts than a Flink deployment, reuse of familiar Kafka Streams execution, and a **single place** (the API) to define and evolve jobs. When requirements grow into heavy stateful analytics, **Flink (or another full engine)** remains the natural step up.

### Examples: standalone Streams vs Streammux vs Flink

| Approach | Example use case |
| -------- | ---------------- |
| **Kafka Streams** (standalone application) | A team ships a fixed microservice that consumes one product topic and writes a derived topic; deployment and lifecycle are entirely custom (CI, runbooks, no shared job registry). |
| **Streammux** | Operators create a **ROUTE_APP** job via the API: filter SIP-related alarms to a lab topic, fan out copies to two outputs, with **topic allowlists** and **one active runner** per job across sites. |
| **Apache Flink** | Compute **session windows** per subscriber, **join** clickstream with catalog updates on event time, or run **continuous aggregations** with savepoints and a dedicated Flink ops model. |

---

## Main parts of the system

| Part | Plain-language role |
| ---- | ------------------- |
| **Job management API** | The front door. Create, update, list, and remove jobs. Shows status, leases, and history-style events as seen through Kafka. |
| **Site orchestrator** | Background service at each site. Watches Kafka, competes for leases, and starts or stops the actual stream worker for jobs assigned to it. |
| **Route worker (route-app)** | The current worker type: a stream processor that moves and filters data between Kafka topics according to the job definition. |
| **Kafka** | Shared infrastructure. Carries job definitions, leases, status, events, and commands. **Streammux does not replace Kafka**; it depends on it. |

The API does **not** call orchestrators directly over the network for normal operation. Everything important flows through Kafka, which makes the system easier to reason about at scale.

---

## Who typically interacts with it

- **Platform or streaming engineers** deploy the containers, connect Kafka, and set environment policies (allowed topics, topic names).
- **Operators or integrators** create and update jobs through the REST API (or automation that calls the same endpoints).
- **Monitoring teams** use standard health and metrics endpoints for uptime and alerting.

---

## How deployment works (high level)

1. **Provide Kafka** that all Streammux components can reach.
2. Run **two** services: the **job management API** (exposes HTTP) and the **site orchestrator** (typically internal-only).
3. Configure **environment variables**: at minimum, where Kafka lives (`KAFKA_BOOTSTRAP_SERVERS`), and usually topic allowlists and naming for your organization.

**Development:** build images from the repository with `docker-compose.dev.yml`.

**Production-style:** pull published images using `docker-compose.yml`, with your registry namespace and image tag.

**Publishing images:** the repository includes a script that builds both images and can push them to a container registry (for example Docker Hub), with version tagging.

Detailed steps, variable tables, and Compose file differences are in **`docs/deployment.md`** in the repo.

---

## How to use it (high level)

1. **Create a job** by sending a JSON description to the API (for example `POST /jobs`). The system validates the configuration and records it in Kafka.
2. **Check status** with read endpoints (list jobs, get one job, optional status and lease views).
3. **Change or retire** jobs with update and delete endpoints. Pause, resume, and restart commands exist on the API; note that some command paths may still be evolving—see repository docs for the current behavior.

**Interactive API docs:** when the API is running, OpenAPI/Swagger UI is available under the standard Springdoc paths (for example `/swagger-ui/index.html`).

The repository includes small shell scripts that create a sample job, list jobs, and delete a job for local testing.

Full endpoint list and script names are in **`docs/usage.md`**.

---

## Dependencies and assumptions

- **Kafka is required** before Streammux is useful.
- Jobs that move data need **appropriate Kafka topics** and, in real environments, **access control** (ACLs) aligned with your security model.
- The management API applies **topic allowlists** when configured; empty allowlist configuration means that category is not restricted—suitable for lab use, risky for open production unless other guards exist.

---

## Current limitations (read before relying on it)

These points summarize engineering status; verify the latest README and release notes before production use:

- Some **command** messages are published to Kafka, but **not every command has a consumer** implemented in this repository yet—day-to-day behavior is still driven strongly by job **desired state** and **leases**.
- Fields like **site affinity** and **priority** exist on paper but are **not** fully driving placement logic today.
- **In-memory** views mean a restart re-learns state from Kafka; plan monitoring accordingly.
- Automated tests do not yet cover every real-world failover story end-to-end.

More detail: **`docs/overview.md`** and the root **`README.md`**.

---

## Where to learn more

| Resource | Content |
| -------- | ------- |
| Repository **`README.md`** | Architecture diagrams, filter expression reference, deep technical notes |
| Repository **`docs/`** | Indexed guides: overview, deployment, usage |
| **`docs/confluence-publish.md`** | Notes on copying this page into Confluence or using the Confluence API (without storing secrets in git) |

---

## Document maintenance

- **Source for this wiki-oriented page:** `docs/STREAMMUX.confluence.md` in the Streammux repository.
- When behavior changes, update the technical docs first, then align this page so non-specialists stay informed.
