# AGENT-MODULE-HOWTO — adding a job runner module to streammux

This document is for an **external coding agent** (or human contributor) who needs to implement a new **job runner** so `site-orchestrator` can start, stop, and report status for a new **job type**. Read [docs/overview.md](docs/overview.md) and the [README.md](README.md) first for control-plane and Kafka-topic context.

---

## What you are building

- **streammux** does not load arbitrary plugins from disk. A runner is a **Maven artifact** on `site-orchestrator`'s classpath that exposes a Spring `@Component` implementing `JobRunner`.
- The orchestrator discovers all `JobRunner` beans, then picks one whose `supports(JobDefinition)` returns true ([`JobRunnerRegistry`](site-orchestrator/src/main/java/io/github/guillebot/streammux/orchestrator/runner/JobRunnerRegistry.java)).
- Resolution uses `findFirst()` on the injected list order. **Exactly one runner must match a given `jobType`**; do not register two beans that both return true for the same definition.

---

## Integration model (non-negotiable)

1. **`job-contracts`** — shared `JobDefinition`, enums, config records, validation, and the `JobRunner` SPI. Any new job type **must** extend contracts so API, Kafka serde, orchestrator, and runners agree on JSON and Java types.
2. **`runners/job-runner-<your-name>`** — new Maven module under [`runners/`](runners/): depends on `job-contracts`, contains your `JobRunner` implementation and supporting code.
3. **`site-orchestrator`** — add a Maven dependency on your runner module so the bean is on the classpath. `SiteOrchestratorApplication` already scans `io.github.guillebot.streammux` ([`SiteOrchestratorApplication.java`](site-orchestrator/src/main/java/io/github/guillebot/streammux/orchestrator/SiteOrchestratorApplication.java)).
4. **Root reactor and Docker** — register the module in the root [`pom.xml`](pom.xml) and mirror the `COPY` / `pom.xml` staging lines in [`Dockerfile.orchestrator`](Dockerfile.orchestrator) and [`Dockerfile.api`](Dockerfile.api) (both stage the full reactor) so images build.

There is **no supported “drop-in external JAR” path** without changing those build files: the product expects **in-tree** (or forked) Maven modules.

---

## Step 1 — Extend `job-contracts`

### 1a. `JobType` enum

Add a new constant in [`job-contracts/.../JobType.java`](job-contracts/src/main/java/io/github/guillebot/streammux/contracts/model/JobType.java). JSON from the API uses the enum **name** (e.g. `RANDOM_SAMPLER`).

### 1b. Configuration record

- Add a dedicated config type under `io.github.guillebot.streammux.contracts.config` (see [`RouteAppConfig`](job-contracts/src/main/java/io/github/guillebot/streammux/contracts/config/RouteAppConfig.java) and [`RandomSamplerConfig`](job-contracts/src/main/java/io/github/guillebot/streammux/contracts/config/RandomSamplerConfig.java)).
- Prefer immutable `record` types; use `Map<String, String>` for optional Kafka client / Streams overrides if that matches existing patterns.

### 1c. `JobDefinition` record

Extend [`JobDefinition`](job-contracts/src/main/java/io/github/guillebot/streammux/contracts/model/JobDefinition.java) with a new component for your config (e.g. `MyRunnerConfig myRunnerConfig`).

**After any `JobDefinition` constructor change**, update every `new JobDefinition(...)` call site, especially:

- [`JobService.java`](job-management-api/src/main/java/io/github/guillebot/streammux/api/service/JobService.java) (`createJob`, `updateJob`, `deleteJob`)
- Tests across `job-contracts`, `job-management-api`, `site-orchestrator`, `integration-tests`

Missing a call site will fail compilation; treat this as a **required mechanical follow-up**.

### 1d. Validation

Extend [`JobDefinitionValidator`](job-contracts/src/main/java/io/github/guillebot/streammux/contracts/validation/JobDefinitionValidator.java):

- When `jobType` is your new type, validate the new config object (non-null, required fields, numeric ranges).
- For any Kafka topic names in config, run them through `TopicValidationPolicy` the same way `ROUTE_APP` / `RANDOM_SAMPLER` do, so [`TopicValidationProperties`](job-management-api/src/main/java/io/github/guillebot/streammux/api/config/TopicValidationProperties.java) (`streammux.validation.topics.*`) continues to enforce allowlists.

Add or extend unit tests in `job-contracts` (see [`JobDefinitionValidatorTest`](job-contracts/src/test/java/io/github/guillebot/streammux/contracts/validation/JobDefinitionValidatorTest.java)).

### 1e. Serde

`JobDefinition` is serialized with Jackson (see [`JsonSerdeFactory`](job-contracts/src/main/java/io/github/guillebot/streammux/contracts/serde/JsonSerdeFactory.java)). JSON property names follow Java bean / record accessor naming (`camelCase`). Ensure your config record fields deserialize cleanly from the API payload you intend to support.

---

## Step 2 — Create module `runners/job-runner-<name>`

All runner modules live under [`runners/`](runners/) so the reactor root stays uncluttered. **Maven `artifactId`** stays `job-runner-<name>`; only the **directory** is nested.

### 2a. Maven layout

Copy an existing runner as a skeleton:

- [`runners/job-runner-route-app/pom.xml`](runners/job-runner-route-app/pom.xml) — Kafka Streams + contracts
- [`runners/job-runner-random-sampler/pom.xml`](runners/job-runner-random-sampler/pom.xml) — slimmer Streams example

In the new module `pom.xml`, the parent must point two levels up:

```xml
<relativePath>../../pom.xml</relativePath>
```

Minimum dependencies for a Streams-based runner today:

- `io.github.guillebot:job-contracts` (version `${project.version}`)
- `org.apache.kafka:kafka-streams` (use `${kafka.version}` from parent)
- `org.springframework:spring-context` (for `@Component` / `@Service` / `@Configuration` if used)
- `org.slf4j:slf4j-api`

Register `<module>runners/job-runner-<name></module>` in the root [`pom.xml`](pom.xml).

### 2b. Implement `JobRunner`

Implement [`JobRunner`](job-contracts/src/main/java/io/github/guillebot/streammux/contracts/spi/JobRunner.java):

| Method | Contract |
| ------ | -------- |
| `supports(JobDefinition)` | Return true **only** for your `JobType` (and optionally extra guards). Must be mutually exclusive with other runners for the same type. |
| `start(JobDefinition, long leaseEpoch)` | Start local processing for `definition.jobId()`. The lease epoch changes when ownership changes; use it in Kafka Streams `application.id` (or equivalent) so a new owner does not collide with committed state. Existing runners call `stop(jobId)` before starting ([`RouteAppRunner`](runners/job-runner-route-app/src/main/java/io/github/guillebot/streammux/routeapp/RouteAppRunner.java), [`RandomSamplerRunner`](runners/job-runner-random-sampler/src/main/java/io/github/guillebot/streammux/randomsampler/RandomSamplerRunner.java)) — follow that idiom to avoid duplicate workers. |
| `stop(String jobId)` | Tear down resources; must be safe to call when already stopped. |
| `status(String jobId)` | Return a non-null [`JobRuntimeStatus`](job-contracts/src/main/java/io/github/guillebot/streammux/contracts/model/JobRuntimeStatus.java) describing local view (`RUNNING` vs `STOPPED`, health, optional `WorkerMetadata`, lag placeholders if unknown). |

Annotate the implementation class with `@Component` so Spring registers it.

**Package naming:** keep implementations under `io.github.guillebot.streammux.<yourmodule>` so component scan picks them up without changing `scanBasePackages`.

### 2c. Non–Kafka Streams runners

The SPI is transport-agnostic. If you do not use Kafka Streams, you still implement the same four methods; manage threads, clients, or subprocesses yourself and map lifecycle to `start` / `stop`.

### 2d. Tests

Add module tests (JUnit 5). Streams runners in-repo use `kafka-streams-test-utils` where applicable.

---

## Step 3 — Wire `site-orchestrator`

In [`site-orchestrator/pom.xml`](site-orchestrator/pom.xml), add:

```xml
<dependency>
  <groupId>io.github.guillebot</groupId>
  <artifactId>job-runner-<name></artifactId>
  <version>${project.version}</version>
</dependency>
```

No Java change is required in the orchestrator if your runner is a discovered `@Component`.

---

## Step 4 — Wire `integration-tests` (if the module ships in this repo)

[`integration-tests/pom.xml`](integration-tests/pom.xml) lists runner artifacts explicitly. Add your `job-runner-<name>` dependency there so Spring test contexts that load the full orchestrator graph resolve all `JobRunner` beans.

---

## Step 5 — Docker image for orchestrator

Update [`Dockerfile.orchestrator`](Dockerfile.orchestrator) and [`Dockerfile.api`](Dockerfile.api):

- `COPY runners/job-runner-<name>/pom.xml runners/job-runner-<name>/pom.xml`
- `COPY runners/job-runner-<name> runners/job-runner-<name>`

The build runs `mvn -pl site-orchestrator -am package`; adding the module to the reactor and orchestrator POM is enough for Maven, but Docker layer copies must list new paths explicitly.

---

## Step 6 — Optional: web UI

If operators should build jobs from the UI, extend:

- [`web-ui/src/types.ts`](web-ui/src/types.ts) — `JobType` union
- [`web-ui/src/jobBuilderOptions.ts`](web-ui/src/jobBuilderOptions.ts) — builder presets / payload shape

The UI is **not** part of the Maven reactor; ship UI changes separately if you use the bundled Vite app.

---

## Operational reminders (from docs)

- Kafka is the source of truth for definitions and leases; runners execute **local** work when this instance holds the lease ([docs/overview.md](docs/overview.md)).
- `job-commands` consumption is **not** implemented in-repo yet; do not rely on command topics for runner lifecycle.
- Topic allowlists may be enabled in deployment; validate topics in `JobDefinitionValidator` as noted above ([docs/deployment.md](docs/deployment.md)).

---

## Verification checklist

1. `mvn -pl job-contracts,site-orchestrator,runners/job-runner-<name> -am test` (expand to full `mvn package` before merge; `-pl :job-runner-<name>` by artifactId also works).
2. Create a job via `POST /jobs` with your `jobType` and config; confirm `job-management-api` accepts it when validation passes.
3. Run orchestrator against a real or Testcontainers Kafka; confirm lease claim starts your runner and status appears on `job-status` for the lease owner.

---

## Reference implementations

| Job type | Runner module |
| -------- | ------------- |
| `ROUTE_APP` | [`runners/job-runner-route-app`](runners/job-runner-route-app) |
| `RANDOM_SAMPLER` | [`runners/job-runner-random-sampler`](runners/job-runner-random-sampler) |

Use these as canonical patterns for topology factories, `application.id` + `leaseEpoch`, in-memory `ConcurrentHashMap` of running jobs, and `JobRuntimeStatus` construction.
