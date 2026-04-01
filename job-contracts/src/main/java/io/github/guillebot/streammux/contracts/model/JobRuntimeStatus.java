package io.github.guillebot.streammux.contracts.model;

import java.time.Instant;

public record JobRuntimeStatus(String jobId, long jobVersion, RuntimeState state, HealthState health, Instant lastHeartbeatAt, WorkerMetadata workerMetadata, String failureReason, LagMetrics lagMetrics) {}
