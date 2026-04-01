# streammux

`streammux` is a Kafka-backed control plane and worker runtime for multi-site stream jobs.

## Modules

- `job-contracts`: shared schemas, topic names, serde, and validation helpers
- `job-management-api`: REST API that writes desired state and serves query views
- `site-orchestrator`: site-local lease manager and worker supervisor
- `job-runner-route-app`: pluggable Kafka Streams runner for `route-app` jobs
- `integration-tests`: Testcontainers-based multi-site failover scenarios
