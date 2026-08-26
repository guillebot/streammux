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
import io.github.guillebot.streammux.orchestrator.config.OrchestratorProperties;
import io.github.guillebot.streammux.orchestrator.config.SiteIdentityProperties;
import io.github.guillebot.streammux.orchestrator.lease.LeaseDecision;
import io.github.guillebot.streammux.orchestrator.lease.LeaseManager;
import io.github.guillebot.streammux.orchestrator.metrics.StreammuxOrchestratorMetrics;
import io.github.guillebot.streammux.orchestrator.runner.JobRunnerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrchestratorServiceTest {

    private static final SiteIdentityProperties SITE = new SiteIdentityProperties("site-a", "instance-a");

    @Mock
    private LeaseManager leaseManager;

    @Mock
    private JobRunnerRegistry jobRunnerRegistry;

    @Mock
    private JobRunner jobRunner;

    @Mock
    private OrchestratorEventPublisher eventPublisher;

    @Mock
    private StreammuxOrchestratorMetrics orchestratorMetrics;

    @Test
    void claimPublishesClaimEventButDefersRunnerStart() {
        JobDefinition definition = jobDefinition();
        JobLease claimedLease = new JobLease("job-1", 1, "site-a", "instance-a", 3, LeaseStatus.CLAIMED, Instant.parse("2024-01-01T00:01:00Z"), Instant.parse("2024-01-01T00:00:00Z"));
        when(leaseManager.decide(eq(definition), isNull(), any())).thenReturn(LeaseDecision.CLAIM);
        when(leaseManager.claim(eq(definition), isNull(), eq(0L), any())).thenReturn(claimedLease);

        OrchestratorService service = newService();

        JobLease result = service.reconcile(definition, null);

        assertEquals(claimedLease, result);
        verify(jobRunner, never()).start(any(), anyLong());
        verify(eventPublisher).publishForDefinition(eq(definition), eq(EventType.CLAIMED), eq("Lease claimed"), anyMap());
    }

    @Test
    void confirmedClaimStartsRunner() {
        JobDefinition definition = jobDefinition();
        JobLease claimedLease = new JobLease("job-1", 1, "site-a", "instance-a", 3, LeaseStatus.CLAIMED, Instant.parse("2024-01-01T00:01:00Z"), Instant.parse("2024-01-01T00:00:00Z"));
        when(leaseManager.decide(eq(definition), isNull(), any())).thenReturn(LeaseDecision.CLAIM);
        when(leaseManager.claim(eq(definition), isNull(), eq(0L), any())).thenReturn(claimedLease);
        when(leaseManager.ownsLease(eq(claimedLease))).thenReturn(true);
        when(jobRunnerRegistry.resolve(eq(definition))).thenReturn(jobRunner);

        OrchestratorService service = newService();
        service.reconcile(definition, null);
        service.maybeStartConfirmedRunner(definition, claimedLease);

        verify(jobRunner).start(definition, 3);
        verify(eventPublisher).publishForDefinition(eq(definition), eq(EventType.STARTED), eq("Runner started"), anyMap());
    }

    @Test
    void releaseOnPausedDesiredStatePublishesStoppedEvent() {
        JobDefinition definition = jobDefinition(DesiredJobState.PAUSED);
        JobLease currentLease = new JobLease("job-1", 1, "site-a", "instance-a", 2, LeaseStatus.RUNNING, Instant.parse("2024-01-01T00:01:00Z"), Instant.parse("2024-01-01T00:00:00Z"));
        when(leaseManager.decide(eq(definition), eq(currentLease), any())).thenReturn(LeaseDecision.RELEASE);
        when(jobRunnerRegistry.resolve(eq(definition))).thenReturn(jobRunner);

        OrchestratorService service = newService();

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

        OrchestratorService service = newService();

        assertNull(service.reconcile(definition, currentLease));
        verify(jobRunner).stop("job-1");
        verify(eventPublisher).publishForDefinition(eq(definition), eq(EventType.RELEASED), eq("Runner released"), anyMap());
    }

    @Test
    void startFailurePublishesFailedEventAndRethrows() {
        JobDefinition definition = jobDefinition(DesiredJobState.ACTIVE);
        JobLease claimedLease = new JobLease("job-1", 1, "site-a", "instance-a", 3, LeaseStatus.CLAIMED, Instant.parse("2024-01-01T00:01:00Z"), Instant.parse("2024-01-01T00:00:00Z"));
        when(leaseManager.decide(eq(definition), isNull(), any())).thenReturn(LeaseDecision.CLAIM);
        when(leaseManager.claim(eq(definition), isNull(), eq(0L), any())).thenReturn(claimedLease);
        when(leaseManager.ownsLease(eq(claimedLease))).thenReturn(true);
        when(jobRunnerRegistry.resolve(eq(definition))).thenReturn(jobRunner);
        doThrow(new IllegalStateException("topology failed")).when(jobRunner).start(definition, 3);

        OrchestratorService service = newService();
        service.reconcile(definition, null);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.maybeStartConfirmedRunner(definition, claimedLease));
        verify(eventPublisher).publishForDefinition(eq(definition), eq(EventType.CLAIMED), eq("Lease claimed"), anyMap());
        verify(eventPublisher).publishForDefinition(eq(definition), eq(EventType.FAILED), eq("topology failed"), anyMap());
    }

    @Test
    void leaseLossPublishesReleasedEvent() {
        JobDefinition definition = jobDefinition();
        JobLease claimedLease = new JobLease("job-1", 1, "site-a", "instance-a", 5, LeaseStatus.CLAIMED, Instant.parse("2024-01-01T00:01:00Z"), Instant.parse("2024-01-01T00:00:00Z"));
        JobLease foreignLease = new JobLease("job-1", 1, "site-b", "instance-b", 6, LeaseStatus.RUNNING, Instant.parse("2024-01-01T00:02:00Z"), Instant.parse("2024-01-01T00:01:30Z"));

        when(leaseManager.decide(eq(definition), isNull(), any())).thenReturn(LeaseDecision.CLAIM);
        when(leaseManager.claim(eq(definition), isNull(), eq(0L), any())).thenReturn(claimedLease);
        when(leaseManager.ownsLease(eq(claimedLease))).thenReturn(true);
        when(jobRunnerRegistry.resolve(eq(definition))).thenReturn(jobRunner);
        when(leaseManager.ownsLease(eq(foreignLease))).thenReturn(false);
        when(leaseManager.decide(eq(definition), eq(foreignLease), any())).thenReturn(LeaseDecision.IGNORE);

        OrchestratorService service = newService();
        service.reconcile(definition, null);
        service.maybeStartConfirmedRunner(definition, claimedLease);

        JobLease result = service.reconcile(definition, foreignLease);

        assertEquals(foreignLease, result);
        verify(jobRunner).start(definition, 5);
        verify(jobRunner).stop("job-1");
        verify(eventPublisher).publishForDefinition(eq(definition), eq(EventType.RELEASED), eq("Stopped after losing lease to site-b/instance-b"), anyMap());
    }

    @Test
    void shouldPublishLeaseRejectsRegressiveEpoch() {
        OrchestratorService service = newService();
        service.observeLease(new JobLease("job-1", 1, "site-b", "instance-b", 6, LeaseStatus.RUNNING, Instant.now(), Instant.now()));

        assertFalse(service.shouldPublishLease(new JobLease("job-1", 1, "site-a", "instance-a", 5, LeaseStatus.RUNNING, Instant.now(), Instant.now())));
        assertTrue(service.shouldPublishLease(new JobLease("job-1", 1, "site-a", "instance-a", 6, LeaseStatus.RUNNING, Instant.now(), Instant.now())));
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

        OrchestratorService service = newService();

        assertEquals(status, service.status("job-1", definition));
    }

    @Test
    void failedRunnerRestartsAfterBackoffWhenLeaseHeld() {
        JobDefinition definition = jobDefinition();
        JobLease runningLease = new JobLease("job-1", 1, "site-a", "instance-a", 2, LeaseStatus.RUNNING, Instant.parse("2024-01-01T00:01:00Z"), Instant.parse("2024-01-01T00:00:00Z"));
        JobRuntimeStatus failedStatus = new JobRuntimeStatus(
            "job-1",
            1,
            RuntimeState.FAILED,
            HealthState.UNHEALTHY,
            Instant.parse("2024-01-01T00:00:00Z"),
            new WorkerMetadata("worker-1", "route-app", "ERROR", Map.of()),
            "Kafka Streams entered ERROR",
            new LagMetrics(0, 0, 0)
        );

        when(leaseManager.decide(eq(definition), isNull(), any())).thenReturn(LeaseDecision.CLAIM);
        when(leaseManager.claim(eq(definition), isNull(), eq(0L), any())).thenReturn(runningLease);
        when(leaseManager.ownsLease(eq(runningLease))).thenReturn(true);
        when(jobRunnerRegistry.resolve(eq(definition))).thenReturn(jobRunner);
        when(jobRunner.status("job-1")).thenReturn(failedStatus);

        OrchestratorService service = new OrchestratorService(leaseManager, SITE, jobRunnerRegistry, eventPublisher, new OrchestratorProperties(5000, 1), orchestratorMetrics);

        service.reconcile(definition, null);
        service.maybeStartConfirmedRunner(definition, runningLease);
        service.maybeRestartFailedRunner(definition, runningLease);
        try {
            Thread.sleep(2);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
        service.maybeRestartFailedRunner(definition, runningLease);

        verify(jobRunner).stop("job-1");
        verify(jobRunner, times(2)).start(definition, 2);
        verify(eventPublisher).publishForDefinition(eq(definition), eq(EventType.STARTED), eq("Runner restarted after failure"), anyMap());
    }

    @Test
    void recoverOwnedRunnerIfMissingStartsRunnerWhenLeaseHeldButNotActive() {
        JobDefinition definition = jobDefinition();
        Instant futureExpiry = Instant.now().plusSeconds(3600);
        JobLease runningLease = new JobLease(
            "job-1",
            1,
            "site-a",
            "instance-a",
            2,
            LeaseStatus.RUNNING,
            futureExpiry,
            Instant.now()
        );
        when(leaseManager.ownsLease(eq(runningLease))).thenReturn(true);
        when(jobRunnerRegistry.resolve(eq(definition))).thenReturn(jobRunner);

        OrchestratorService service = newService();
        service.recoverOwnedRunnerIfMissing(definition, runningLease);

        verify(jobRunner).start(definition, 2);
        verify(eventPublisher).publishForDefinition(
            eq(definition),
            eq(EventType.STARTED),
            eq("Runner recovered after restart"),
            anyMap()
        );
    }

    @Test
    void failedRunnerWaitsForRestartBackoff() {
        JobDefinition definition = jobDefinition();
        JobLease runningLease = new JobLease("job-1", 1, "site-a", "instance-a", 2, LeaseStatus.RUNNING, Instant.parse("2024-01-01T00:01:00Z"), Instant.parse("2024-01-01T00:00:00Z"));
        JobRuntimeStatus failedStatus = new JobRuntimeStatus(
            "job-1",
            1,
            RuntimeState.FAILED,
            HealthState.UNHEALTHY,
            Instant.parse("2024-01-01T00:00:00Z"),
            new WorkerMetadata("worker-1", "route-app", "ERROR", Map.of()),
            "Kafka Streams entered ERROR",
            new LagMetrics(0, 0, 0)
        );

        when(leaseManager.decide(eq(definition), isNull(), any())).thenReturn(LeaseDecision.CLAIM);
        when(leaseManager.claim(eq(definition), isNull(), eq(0L), any())).thenReturn(runningLease);
        when(leaseManager.ownsLease(eq(runningLease))).thenReturn(true);
        when(jobRunnerRegistry.resolve(eq(definition))).thenReturn(jobRunner);
        when(jobRunner.status("job-1")).thenReturn(failedStatus);

        OrchestratorService service = new OrchestratorService(leaseManager, SITE, jobRunnerRegistry, eventPublisher, new OrchestratorProperties(5000, 60_000), orchestratorMetrics);

        service.reconcile(definition, null);
        service.maybeStartConfirmedRunner(definition, runningLease);
        service.maybeRestartFailedRunner(definition, runningLease);

        verify(jobRunner, times(1)).start(definition, 2);
        verify(jobRunner, never()).stop("job-1");
    }

    private OrchestratorService newService() {
        return new OrchestratorService(leaseManager, SITE, jobRunnerRegistry, eventPublisher, disabledRestart(), orchestratorMetrics);
    }

    private static OrchestratorProperties disabledRestart() {
        return new OrchestratorProperties(5000, 0);
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
            new LeasePolicy(10, 30, 0, true),
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
