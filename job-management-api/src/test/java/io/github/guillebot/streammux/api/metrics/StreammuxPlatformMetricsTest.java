package io.github.guillebot.streammux.api.metrics;

import io.github.guillebot.streammux.api.service.JobStateStore;
import io.github.guillebot.streammux.api.service.JobStatusResolver;
import io.github.guillebot.streammux.api.service.PlatformHealthService;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LagMetrics;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.LeaseStatus;
import io.github.guillebot.streammux.contracts.model.RuntimeState;
import io.github.guillebot.streammux.contracts.model.WorkerMetadata;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreammuxPlatformMetricsTest {

    private JobStateStore stateStore;
    private PlatformHealthService platformHealthService;
    private SimpleMeterRegistry registry;
    private StreammuxPlatformMetrics metrics;

    @BeforeEach
    void setUp() {
        stateStore = new JobStateStore();
        platformHealthService = mock(PlatformHealthService.class);
        registry = new SimpleMeterRegistry();
        metrics = new StreammuxPlatformMetrics(registry, stateStore, platformHealthService, new JobStatusResolver());
    }

    @Test
    void refreshJobMetricsPublishesConfiguredRuntimeAndLagSeries() {
        Instant now = Instant.now();
        stateStore.upsertDefinition(jobDefinition("job-a", JobType.ROUTE_APP, DesiredJobState.ACTIVE));
        stateStore.upsertStatus(new JobRuntimeStatus(
            "job-a",
            1,
            RuntimeState.RUNNING,
            HealthState.HEALTHY,
            now.minusSeconds(5),
            new WorkerMetadata("worker-a", "route-app", "RUNNING", Map.of()),
            null,
            new LagMetrics(42, 7, 100)
        ));
        stateStore.upsertLease(new JobLease(
            "job-a",
            1,
            "rednet",
            "kstreams1.srv.hcvlny.alticeusa.net",
            2,
            LeaseStatus.RUNNING,
            now.plusSeconds(60),
            now.minusSeconds(5)
        ));

        metrics.refreshJobMetrics();

        assertEquals(1.0, registry.get("streammux.jobs.configured").tag("desired_state", "ACTIVE").tag("job_type", "ROUTE_APP").gauge().value());
        assertEquals(1.0, registry.get("streammux.jobs.runtime").tag("state", "RUNNING").tag("health", "HEALTHY").gauge().value());
        assertEquals(42.0, registry.get("streammux.job.input_lag").tag("job_id", "job-a").gauge().value());
        assertEquals(7.0, registry.get("streammux.job.output_rate").tag("job_id", "job-a").gauge().value());
        assertEquals(1.0, registry.get("streammux.job.lease_holder")
            .tag("job_id", "job-a")
            .tag("site_id", "rednet")
            .tag("instance_id", "kstreams1.srv.hcvlny.alticeusa.net")
            .gauge()
            .value());
    }

    @Test
    void refreshPlatformHealthUsesKafkaProbeStatus() {
        when(platformHealthService.snapshot()).thenReturn(new PlatformHealthService.PlatformHealth(
            "UP",
            Instant.parse("2024-01-01T00:00:00Z"),
            new PlatformHealthService.ModuleHealth("job-management-api", "UP"),
            new PlatformHealthService.KafkaHealth("UP", "kb101:19092", "cluster-1", 5, List.of()),
            new PlatformHealthService.ReadModelHealth(3, 2, 2, 1)
        ));

        metrics.refreshPlatformHealth();

        assertEquals(1.0, registry.get("streammux.platform.kafka.up").gauge().value());
        assertEquals(3.0, registry.get("streammux.read_model.jobs").gauge().value());
        assertEquals(2.0, registry.get("streammux.read_model.leases").gauge().value());
        assertEquals(2.0, registry.get("streammux.read_model.statuses").gauge().value());
    }

    private static JobDefinition jobDefinition(String jobId, JobType jobType, DesiredJobState desiredState) {
        return new JobDefinition(
            jobId,
            1,
            jobType,
            desiredState,
            1,
            "rednet",
            LeasePolicy.defaults(),
            1,
            null,
            null,
            null,
            Map.of(),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }
}
