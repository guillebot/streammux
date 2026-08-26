package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.LeaseStatus;
import io.github.guillebot.streammux.contracts.model.RuntimeState;
import io.github.guillebot.streammux.contracts.model.WorkerMetadata;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobStatusResolverTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");

    @Test
    void resolveStaleHeartbeatMarksStatusStopped() {
        JobStatusResolver resolver = new JobStatusResolver(Clock.fixed(NOW, ZoneOffset.UTC));
        JobDefinition definition = activeJob("job-1", new LeasePolicy(10, 30, 5000, true));
        JobLease lease = new JobLease(
            "job-1",
            1,
            "site-a",
            "instance-a",
            2,
            LeaseStatus.RUNNING,
            NOW.plusSeconds(60),
            NOW.minusSeconds(5)
        );
        JobRuntimeStatus rawStatus = new JobRuntimeStatus(
            "job-1",
            1,
            RuntimeState.RUNNING,
            HealthState.HEALTHY,
            NOW.minusSeconds(45),
            new WorkerMetadata("worker-1", "route-app", "RUNNING", Map.of()),
            null,
            null
        );

        Optional<JobRuntimeStatus> resolved = resolver.resolve(
            Optional.of(rawStatus),
            Optional.of(definition),
            Optional.of(lease)
        );

        assertTrue(resolved.isPresent());
        assertEquals(RuntimeState.STOPPED, resolved.get().state());
        assertEquals(HealthState.UNHEALTHY, resolved.get().health());
        assertEquals("Status stale: no heartbeat within 30s lease window", resolved.get().failureReason());
    }

    @Test
    void resolveExpiredLeaseMarksStatusStopped() {
        JobStatusResolver resolver = new JobStatusResolver(Clock.fixed(NOW, ZoneOffset.UTC));
        JobDefinition definition = activeJob("job-1", LeasePolicy.defaults());
        JobLease expiredLease = new JobLease(
            "job-1",
            1,
            "site-a",
            "instance-a",
            2,
            LeaseStatus.RUNNING,
            NOW.minusSeconds(10),
            NOW.minusSeconds(5)
        );
        JobRuntimeStatus rawStatus = new JobRuntimeStatus(
            "job-1",
            1,
            RuntimeState.RUNNING,
            HealthState.HEALTHY,
            NOW.minusSeconds(5),
            new WorkerMetadata("worker-1", "route-app", "RUNNING", Map.of()),
            null,
            null
        );

        Optional<JobRuntimeStatus> resolved = resolver.resolve(
            Optional.of(rawStatus),
            Optional.of(definition),
            Optional.of(expiredLease)
        );

        assertTrue(resolved.isPresent());
        assertEquals(RuntimeState.STOPPED, resolved.get().state());
        assertEquals(HealthState.UNHEALTHY, resolved.get().health());
        assertEquals("Lease expired; runner is not active", resolved.get().failureReason());
    }

    private static JobDefinition activeJob(String jobId, LeasePolicy leasePolicy) {
        return new JobDefinition(
            jobId,
            1,
            JobType.ROUTE_APP,
            DesiredJobState.ACTIVE,
            1,
            "site-a",
            leasePolicy,
            1,
            null,
            null,
            null,
            Map.of(),
            List.of(),
            NOW,
            "tester"
        );
    }
}
