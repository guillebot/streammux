# Initial Prompt

Design a Java-based, Maven-built, Kafka Streams platform for centrally managed stream-processing jobs running across multiple physical locations.

## Core Idea

The system has a central job management plane and one or more site-local orchestrators. Each orchestrator runs in a Docker Compose deployment at a specific location. All locations connect to the same central job management source and compete to claim, run, monitor, and fail over jobs.

## Runtime Model

- The orchestrator continuously reads the global job list from the central site.
- For each assigned job, the orchestrator starts the correct worker runtime for that job type.
- Worker runtimes are pluggable. In the initial version there is only one supported job type: `route-app`.
- The design must support multiple job types in the future without changing the orchestration model.

## Initial Job Type: `route-app`

`route-app` is a Kafka Streams application whose full runtime configuration comes from the job definition.

For each `route-app` job:

- It reads messages from a configured input topic.
- It evaluates one or more configured filters.
- For each matching filter, it publishes the message to a configured output topic.
- Input and output payloads may be encoded as either JSON or Protobuf.
- Topic names, filter rules, serialization settings, and app-specific stream properties come from the job description.

## Problem To Solve

Design a central job management solution for a multi-site deployment model where:

- Multiple Docker Compose deployments run in different locations.
- All locations must pull from the same shared central job catalog.
- Only one location should actively own and run a given job at a time unless the design explicitly supports partitioned or parallel execution.
- Jobs must be claimable, renewable, releasable, restartable, and failover-capable.
- The system should handle site loss and allow another site to take over work safely.

## Design Goals

- Centralized job definition and control plane
- Site-local execution with centralized coordination
- Safe lease or ownership model for active jobs
- Support for failover across sites
- Pluggable job runners by job type
- Declarative job definitions
- Clear operational visibility into job state, leases, health, and events
- Compatibility with Docker Compose based deployments

## Expected Architecture Themes

The design should cover:

- A central API or control service for job creation, updates, pause, resume, restart, and delete
- A shared durable source of truth for job definitions and job state
- A coordination model for lease claiming and renewal across sites
- How orchestrators discover jobs and react to changes
- How job events, heartbeats, health, and status are published
- How `route-app` configurations are validated before execution
- How JSON and Protobuf payload handling is described in the job contract
- How to add new job types later without coupling orchestration to one specific app

## Deliverable Framing

Produce a design for:

1. The central job management architecture
2. The job contract model
3. The orchestration and lease model
4. The `route-app` runtime contract
5. Multi-site failover behavior
6. Operational concerns such as observability, health, rollout, and recovery

The design should optimize for correctness, operability, and extensibility rather than a one-off prototype.
