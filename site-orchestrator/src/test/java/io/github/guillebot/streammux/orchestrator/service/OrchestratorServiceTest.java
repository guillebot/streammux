package io.github.guillebot.streammux.orchestrator.service;

import io.github.guillebot.streammux.contracts.model.EventType;
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
import io.github.guillebot.streammux.contracts.spi.JobRunner;
import io.github.guillebot.streammux.orchestrator.lease.LeaseDecision;
import io.github.guillebot.streammux.orchestrator.lease.LeaseManager;
import io.github.guillebot.streammux.orchestrator.runner.JobRunnerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrchestratorServiceTest {

    @Mock
    private LeaseManager leaseManager;

    @Mock
    private JobRunnerRegistry jobRunnerRegistry;

    @Mock
    private JobRunner jobRunner;

    @Mock
    private OrchestratorEventPublisher eventPublisher;

    @Test
    void claimStartsRunnerAndReturnsNewLease() {
        JobDefinition definition = jobDefinition();
        JobLease claimedLease = new JobLease("job-1", 1, "site-a", "instance-a", 3, LeaseStatus.CLAIMED, Instant.parse("2024-01-01T00:01:00Z"), Instant.parse("2024-01-01T00:00:00Z"));
        when(leaseManager.decide(eq(definition), isNull(), any())).thenReturn(LeaseDecision.CLAIM);
        when(leaseManager.claim(eq(definition), isNull(), any())).thenReturn(claimedLease);
        when(jobRunnerRegistry.resolve(eq(definition))).thenReturn(jobRunner);

        OrchestratorService service = new OrchestratorService(leaseManager, jobRunnerRegistry, eventPublisher);

        JobLease result = service.reconcile(definition, null);

        assertEquals(claimedLease, result);
        verify(jobRunner).start(definition, 3);
        verify(eventPublisher).publishForDefinition(eq(definition), eq(EventType.CLAIMED), eq("Lease claimed"), anyMap());
        verify(eventPublisher).publishForDefinition(eq(definition), eq(EventType.STARTED), eq("Runner started"), anyMap());
    }

    @Test
    void releaseOnPausedDesiredStatePublishesStoppedEvent() {
        JobDefinition definition = jobDefinition(DesiredJobState.PAUSED);
        JobLease currentLease = new JobLease("job-1", 1, "site-a", "instance-a", 2, LeaseStatus.RUNNING, Instant.parse("2024-01-01T00:01:00Z"), Instant.parse("2024-01-01T00:00:00Z"));
        when(leaseManager.decide(eq(definition), eq(currentLease), any())).thenReturn(LeaseDecision.RELEASE);
        when(jobRunnerRegistry.resolve(eq(definition))).thenReturn(jobRunner);

        OrchestratorService service = new OrchestratorService(leaseManager, jobRunnerRegistry, eventPublisher);

        JobLease result = service.reconcile(definition, currentLease);

        assertNull(result);
        verify(jobRunner).stop("job-1");
        verify(eventPublisher).publishForDefinition(eq(definition), eq(EventType.STOPPED), eq("Runner stopped (desiredState=PAUSED)"), anyMap());
    }

    @Test
    void releaseOnActiveDesiredStatePublishesReleasedEvent() {
        JobDefinition definition = jobDefinition(DesiredJobState.ACTIVE);
        JobLease currentLease = new JobLease("job-1", 1, "site-a", "instance-a", 2, LeaseStatus.RUNNING, Instant.parse("2024-01-01T00:01:00Z"), Instant.parse("2024-01-01T00:00:00Z"));
        when(leaseManager.decide(eq(definition), eq(currentLease), any())).thenReturn(LeaseDecision.RELEASE);
        when(jobRunnerRegistry.resolve(eq(definition))).thenReturn(jobRunner);

        OrchestratorService service = new OrchestratorService(leaseManager, jobRunnerRegistry, eventPublisher);

        assertNull(service.reconcile(definition, currentLease));
        verify(jobRunner).stop("job-1");
        verify(eventPublisher).publishForDefinition(eq(definition), eq(EventType.RELEASED), eq("Runner released"), anyMap());
    }

    @Test
    void startFailurePublishesFailedEventAndRethrows() {
        JobDefinition definition = jobDefinition(DesiredJobState.ACTIVE);
        JobLease claimedLease = new JobLease("job-1", 1, "site-a", "instance-a", 3, LeaseStatus.CLAIMED, Instant.parse("2024-01-01T00:01:00Z"), Instant.parse("2024-01-01T00:00:00Z"));
        when(leaseManager.decide(eq(definition), isNull(), any())).thenReturn(LeaseDecision.CLAIM);
        when(leaseManager.claim(eq(definition), isNull(), any())).thenReturn(claimedLease);
        when(jobRunnerRegistry.resolve(eq(definition))).thenReturn(jobRunner);
        doThrow(new IllegalStateException("topology failed")).when(jobRunner).start(definition, 3);

        OrchestratorService service = new OrchestratorService(leaseManager, jobRunnerRegistry, eventPublisher);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.reconcile(definition, null));
        verify(eventPublisher).publishForDefinition(eq(definition), eq(EventType.CLAIMED), eq("Lease claimed"), anyMap());
        verify(eventPublisher).publishForDefinition(eq(definition), eq(EventType.FAILED), eq("topology failed"), anyMap());
    }

    @Test
    void leaseLossPublishesReleasedEvent() {
        JobDefinition definition = jobDefinition();
        JobLease claimedLease = new JobLease("job-1", 1, "site-a", "instance-a", 5, LeaseStatus.CLAIMED, Instant.parse("2024-01-01T00:01:00Z"), Instant.parse("2024-01-01T00:00:00Z"));
        JobLease foreignLease = new JobLease("job-1", 1, "site-b", "instance-b", 6, LeaseStatus.RUNNING, Instant.parse("2024-01-01T00:02:00Z"), Instant.parse("2024-01-01T00:01:30Z"));

        when(leaseManager.decide(eq(definition), isNull(), any())).thenReturn(LeaseDecision.CLAIM);
        when(leaseManager.claim(eq(definition), isNull(), any())).thenReturn(claimedLease);
        when(jobRunnerRegistry.resolve(eq(definition))).thenReturn(jobRunner);
        when(leaseManager.ownsLease(eq(foreignLease))).thenReturn(false);
        when(leaseManager.decide(eq(definition), eq(foreignLease), any())).thenReturn(LeaseDecision.IGNORE);

        OrchestratorService service = new OrchestratorService(leaseManager, jobRunnerRegistry, eventPublisher);
        service.reconcile(definition, null);

        JobLease result = service.reconcile(definition, foreignLease);

        assertEquals(foreignLease, result);
        verify(jobRunner).start(definition, 5);
        verify(jobRunner).stop("job-1");
        verify(eventPublisher).publishForDefinition(eq(definition), eq(EventType.RELEASED), eq("Stopped after losing lease to site-b/instance-b"), anyMap());
    }

    @Test
    void statusDelegatesToResolvedRunner() {
        JobDefinition definition = jobDefinition();
        JobRuntimeStatus status = new JobRuntimeStatus(
            "job-1",
            1,
            RuntimeState.RUNNING,
            HealthState.HEALTHY,
            Instant.parse("2024-01-01T00:00:00Z"),
            new WorkerMetadata("worker-1", "route-app", "RUNNING", Map.of()),
            null,
            new LagMetrics(0, 0, 0)
        );
        when(jobRunnerRegistry.resolve(eq(definition))).thenReturn(jobRunner);
        when(jobRunner.status("job-1")).thenReturn(status);

        OrchestratorService service = new OrchestratorService(leaseManager, jobRunnerRegistry, eventPublisher);

        assertEquals(status, service.status("job-1", definition));
    }

    private static JobDefinition jobDefinition() {
        return jobDefinition(DesiredJobState.ACTIVE);
    }

    private static JobDefinition jobDefinition(DesiredJobState desiredState) {
        return new JobDefinition(
            "job-1",
            1,
            JobType.ROUTE_APP,
            desiredState,
            1,
            "site-a",
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
