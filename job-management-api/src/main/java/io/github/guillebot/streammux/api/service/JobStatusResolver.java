package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.RuntimeState;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Derives operator-facing runtime status from the compacted read model.
 * Cached {@code job-status} records can outlive the runner; this layer reconciles
 * them against lease expiry and heartbeat age for ACTIVE jobs.
 */
public final class JobStatusResolver {

    private final Clock clock;

    public JobStatusResolver() {
        this(Clock.systemUTC());
    }

    JobStatusResolver(Clock clock) {
        this.clock = clock;
    }

    public Optional<JobRuntimeStatus> resolve(
        Optional<JobRuntimeStatus> rawStatus,
        Optional<JobDefinition> definition,
        Optional<JobLease> lease
    ) {
        if (definition.isEmpty()) {
            return rawStatus;
        }
        JobDefinition job = definition.get();
        if (job.desiredState() != DesiredJobState.ACTIVE) {
            return rawStatus;
        }

        Instant now = clock.instant();
        if (lease.isPresent() && lease.get().isExpired(now)) {
            return Optional.of(staleStatus(rawStatus, job.jobId(), "Lease expired; runner is not active"));
        }

        if (rawStatus.isEmpty()) {
            return Optional.of(missingStatus(job.jobId()));
        }

        JobRuntimeStatus status = rawStatus.get();
        Instant heartbeat = status.lastHeartbeatAt();
        if (heartbeat == null) {
            return Optional.of(staleStatus(Optional.of(status), job.jobId(), "No heartbeat reported"));
        }

        long staleAfterSeconds = Math.max(1L, job.leasePolicy().leaseDurationSeconds());
        if (now.isAfter(heartbeat.plusSeconds(staleAfterSeconds))) {
            return Optional.of(
                staleStatus(
                    Optional.of(status),
                    job.jobId(),
                    "Status stale: no heartbeat within " + staleAfterSeconds + "s lease window"
                )
            );
        }

        return rawStatus;
    }

    private static JobRuntimeStatus staleStatus(Optional<JobRuntimeStatus> rawStatus, String jobId, String reason) {
        if (rawStatus.isPresent()) {
            JobRuntimeStatus status = rawStatus.get();
            return new JobRuntimeStatus(
                status.jobId(),
                status.jobVersion(),
                RuntimeState.STOPPED,
                HealthState.UNHEALTHY,
                status.lastHeartbeatAt(),
                status.workerMetadata(),
                reason,
                null
            );
        }
        return missingStatus(jobId, reason);
    }

    private static JobRuntimeStatus missingStatus(String jobId) {
        return missingStatus(jobId, "No runtime status reported for ACTIVE job");
    }

    private static JobRuntimeStatus missingStatus(String jobId, String reason) {
        return new JobRuntimeStatus(
            jobId,
            0,
            RuntimeState.STOPPED,
            HealthState.UNHEALTHY,
            null,
            null,
            reason,
            null
        );
    }
}
