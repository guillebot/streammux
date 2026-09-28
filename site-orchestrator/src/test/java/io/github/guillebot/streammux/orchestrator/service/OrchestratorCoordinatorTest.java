package io.github.guillebot.streammux.orchestrator.service;

import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.LeaseStatus;
import io.github.guillebot.streammux.contracts.model.TopicNames;
import io.github.guillebot.streammux.contracts.spi.JobRunner;
import io.github.guillebot.streammux.orchestrator.config.KafkaTopicProperties;
import io.github.guillebot.streammux.orchestrator.config.OrchestratorProperties;
import io.github.guillebot.streammux.orchestrator.config.SiteIdentityProperties;
import io.github.guillebot.streammux.orchestrator.lease.LeaseManager;
import io.github.guillebot.streammux.orchestrator.metrics.StreammuxOrchestratorMetrics;
import io.github.guillebot.streammux.orchestrator.runner.JobRunnerRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.event.ListenerContainerIdleEvent;
import org.springframework.kafka.listener.ConsumerSeekAware;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrchestratorCoordinatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final SiteIdentityProperties SITE = new SiteIdentityProperties("site-a", "instance-a");
    private static final TopicPartition LEASE_PARTITION = new TopicPartition(TopicNames.JOB_LEASES, 0);

    @Mock
    private JobRunnerRegistry jobRunnerRegistry;
    @Mock
    private JobRunner jobRunner;
    @Mock
    private OrchestratorEventPublisher eventPublisher;
    @Mock
    private StreammuxOrchestratorMetrics orchestratorMetrics;
    @Mock
    private KafkaOrchestratorPublisher publisher;
    @Mock
    private ConsumerSeekAware.ConsumerSeekCallback callback;

    private OrchestratorStateStore stateStore;
    private OrchestratorService orchestratorService;
    private OrchestratorCoordinator coordinator;

    @BeforeEach
    void setUp() {
        stateStore = new OrchestratorStateStore();
        LeaseManager leaseManager = new LeaseManager(SITE);
        orchestratorService = new OrchestratorService(
            leaseManager,
            SITE,
            jobRunnerRegistry,
            eventPublisher,
            new OrchestratorProperties(5000, 0, 0, 0),
            orchestratorMetrics
        );
        coordinator = new OrchestratorCoordinator(
            stateStore,
            orchestratorService,
            leaseManager,
            publisher,
            orchestratorMetrics,
            KafkaTopicProperties.defaults()
        );
    }

    @Test
    void seeksToBeginningOnlyOnFirstAssignment() {
        TopicPartition leases = new TopicPartition("jobleases", 0);

        coordinator.onPartitionsAssigned(Map.of(leases, 12L), callback);
        coordinator.onPartitionsAssigned(Map.of(leases, 40L), callback);

        verify(callback, times(1)).seekToBeginning("jobleases", 0);
    }

    @Test
    void rebalanceDoesNotSeekToBeginningWhenPartitionAlreadyAssigned() {
        TopicPartition leases = new TopicPartition("jobleases", 0);

        coordinator.onPartitionsAssigned(Map.of(leases, 0L), callback);
        coordinator.onPartitionsAssigned(Map.of(leases, 99L), callback);

        verify(callback, times(1)).seekToBeginning("jobleases", 0);
        verify(callback, never()).seek("jobleases", 0, 99L);
    }

    @Test
    void compactedReplayOfOlderLeaseRecordsDoesNotPublishLowerEpoch() {
        when(jobRunnerRegistry.resolve(any())).thenReturn(jobRunner);
        when(jobRunner.status(any())).thenReturn(null);
        stateStore.upsertDefinition(jobDefinition());

        Instant now = Instant.now();
        JobLease stale = new JobLease(
            "job-1",
            1,
            "site-b",
            "instance-b",
            3,
            LeaseStatus.RUNNING,
            now.minusSeconds(120),
            now.minusSeconds(180)
        );
        JobLease live = new JobLease(
            "job-1",
            1,
            "site-b",
            "instance-b",
            12,
            LeaseStatus.RUNNING,
            now.plusSeconds(3600),
            now
        );

        AtomicLong position = new AtomicLong(0);
        Consumer<String, byte[]> consumer = leaseConsumer(position, 2L);

        position.set(1);
        coordinator.onJobLease(leaseRecord(stale, 0), consumer);
        verify(publisher, never()).publishLease(any());
        assertEquals(3, stateStore.getLease("job-1").orElseThrow().leaseEpoch());

        position.set(2);
        coordinator.onJobLease(leaseRecord(live, 1), consumer);

        assertEquals(12, stateStore.getLease("job-1").orElseThrow().leaseEpoch());
        verify(publisher, never()).publishLease(any());
        assertTrue(orchestratorService.shouldPublishLease(live));
        assertTrue(!orchestratorService.shouldPublishLease(stale));
    }

    @Test
    void newInstanceDoesNotStealLiveUnexpiredLeaseAfterCatchUp() {
        when(jobRunnerRegistry.resolve(any())).thenReturn(jobRunner);
        when(jobRunner.status(any())).thenReturn(null);
        stateStore.upsertDefinition(jobDefinition());

        Instant now = Instant.now();
        JobLease live = new JobLease(
            "job-1",
            1,
            "kstreams1",
            "orchestrator-1",
            40,
            LeaseStatus.RUNNING,
            now.plusSeconds(600),
            now
        );

        AtomicLong position = new AtomicLong(1);
        Consumer<String, byte[]> consumer = leaseConsumer(position, 1L);
        coordinator.onJobLease(leaseRecord(live, 0), consumer);

        verify(publisher, never()).publishLease(any());
        assertEquals("kstreams1", stateStore.getLease("job-1").orElseThrow().leaseOwnerSite());
        assertEquals(40, stateStore.getLease("job-1").orElseThrow().leaseEpoch());
    }

    @Test
    void catchUpThenExpiredUnownedLeaseMayClaimHigherEpoch() {
        when(jobRunnerRegistry.resolve(any())).thenReturn(jobRunner);
        when(jobRunner.status(any())).thenReturn(null);
        stateStore.upsertDefinition(jobDefinition());

        Instant now = Instant.now();
        JobLease expired = new JobLease(
            "job-1",
            1,
            "site-b",
            "instance-b",
            7,
            LeaseStatus.RUNNING,
            now.minusSeconds(5),
            now.minusSeconds(30)
        );

        AtomicLong position = new AtomicLong(1);
        Consumer<String, byte[]> consumer = leaseConsumer(position, 1L);
        coordinator.onJobLease(leaseRecord(expired, 0), consumer);

        ArgumentCaptor<JobLease> captor = ArgumentCaptor.forClass(JobLease.class);
        verify(publisher).publishLease(captor.capture());
        assertEquals(8, captor.getValue().leaseEpoch());
        assertEquals("instance-a", captor.getValue().leaseOwnerInstance());
    }

    @Test
    void peerDoesNotClaimWhenLiveRenewIsLaterInTheSamePollBatch() {
        when(jobRunnerRegistry.resolve(any())).thenReturn(jobRunner);
        when(jobRunner.status(any())).thenReturn(null);
        stateStore.upsertDefinition(jobDefinition());

        Instant now = Instant.now();
        JobLease expiredSnapshot = new JobLease(
            "job-1",
            1,
            "kstreams1",
            "orchestrator-1",
            40,
            LeaseStatus.RUNNING,
            now.minusSeconds(5),
            now.minusSeconds(30)
        );
        JobLease liveRenew = new JobLease(
            "job-1",
            1,
            "kstreams1",
            "orchestrator-1",
            40,
            LeaseStatus.RUNNING,
            now.plusSeconds(600),
            now
        );

        // position() is already at the batch end; both records arrive in the same poll batch.
        AtomicLong position = new AtomicLong(2);
        Consumer<String, byte[]> consumer = leaseConsumer(position, 2L);

        coordinator.onJobLeases(List.of(leaseRecord(expiredSnapshot, 0), leaseRecord(liveRenew, 1)), consumer);

        verify(publisher, never()).publishLease(any());
        JobLease stored = stateStore.getLease("job-1").orElseThrow();
        assertEquals("kstreams1", stored.leaseOwnerSite());
        assertEquals("orchestrator-1", stored.leaseOwnerInstance());
        assertEquals(40, stored.leaseEpoch());
        assertTrue(stored.leaseExpiresAt().isAfter(now));
    }

    @Test
    void sameInstanceRenewsExpiredLeaseWithoutRaisingEpoch() {
        when(jobRunnerRegistry.resolve(any())).thenReturn(jobRunner);
        when(jobRunner.status(any())).thenReturn(null);
        stateStore.upsertDefinition(jobDefinition());

        Instant now = Instant.now();
        JobLease owned = new JobLease(
            "job-1",
            1,
            "site-a",
            "instance-a",
            40,
            LeaseStatus.RUNNING,
            now.minusSeconds(5),
            now.minusSeconds(30)
        );

        AtomicLong position = new AtomicLong(1);
        Consumer<String, byte[]> consumer = leaseConsumer(position, 1L);
        coordinator.onJobLease(leaseRecord(owned, 0), consumer);

        ArgumentCaptor<JobLease> captor = ArgumentCaptor.forClass(JobLease.class);
        verify(publisher).publishLease(captor.capture());
        assertEquals(40, captor.getValue().leaseEpoch());
        assertEquals("site-a", captor.getValue().leaseOwnerSite());
        assertEquals("instance-a", captor.getValue().leaseOwnerInstance());
        assertEquals(LeaseStatus.RUNNING, captor.getValue().status());
    }

    @Test
    void idleAtHighWatermarkWithNoSurvivingRecordsCompletesCatchUp() {
        when(jobRunnerRegistry.resolve(any())).thenReturn(jobRunner);
        when(jobRunner.status(any())).thenReturn(null);
        stateStore.upsertDefinition(jobDefinition());

        // Non-empty offset range whose records were all compacted away: nothing is ever delivered.
        AtomicLong position = new AtomicLong(100);
        Consumer<String, byte[]> consumer = leaseConsumer(position, 100L);
        ListenerContainerIdleEvent event = new ListenerContainerIdleEvent(
            coordinator,
            coordinator,
            1_000L,
            "job-leases",
            List.of(LEASE_PARTITION),
            consumer,
            false
        );

        coordinator.onListenerIdle(event);

        ArgumentCaptor<JobLease> captor = ArgumentCaptor.forClass(JobLease.class);
        verify(publisher).publishLease(captor.capture());
        assertEquals(1, captor.getValue().leaseEpoch());
        assertEquals("instance-a", captor.getValue().leaseOwnerInstance());
    }

    /**
     * Regression for the 20260928-04 hang: the last delivered record of the batch sits below the
     * high watermark (offsets compacted away / control records), so waiting for "the record at
     * end-1" never returns. Catch-up must complete from position == end after the batch.
     */
    @Test
    void catchUpCompletesWhenLastDeliveredOffsetIsBelowHighWatermark() {
        when(jobRunnerRegistry.resolve(any())).thenReturn(jobRunner);
        when(jobRunner.status(any())).thenReturn(null);
        stateStore.upsertDefinition(jobDefinition());

        Instant now = Instant.now();
        JobLease older = new JobLease("job-1", 1, "site-b", "instance-b", 6, LeaseStatus.RUNNING,
            now.minusSeconds(120), now.minusSeconds(180));
        JobLease expired = new JobLease("job-1", 1, "site-b", "instance-b", 7, LeaseStatus.RUNNING,
            now.minusSeconds(5), now.minusSeconds(30));

        // Delivered offsets 0 and 1; offset 2 no longer exists, high watermark is 3.
        AtomicLong position = new AtomicLong(3);
        Consumer<String, byte[]> consumer = leaseConsumer(position, 3L);

        coordinator.onJobLeases(List.of(leaseRecord(older, 0), leaseRecord(expired, 1)), consumer);

        ArgumentCaptor<JobLease> captor = ArgumentCaptor.forClass(JobLease.class);
        verify(publisher).publishLease(captor.capture());
        assertEquals(8, captor.getValue().leaseEpoch());
        assertEquals("instance-a", captor.getValue().leaseOwnerInstance());
        verify(consumer, times(1)).endOffsets(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void catchUpCompletesWhenAnotherNonEmptyPartitionHasNoSurvivingRecords() {
        when(jobRunnerRegistry.resolve(any())).thenReturn(jobRunner);
        when(jobRunner.status(any())).thenReturn(null);
        stateStore.upsertDefinition(jobDefinition());

        Instant now = Instant.now();
        JobLease expired = new JobLease("job-1", 1, "site-b", "instance-b", 7, LeaseStatus.RUNNING,
            now.minusSeconds(5), now.minusSeconds(30));

        TopicPartition emptied = new TopicPartition(TopicNames.JOB_LEASES, 1);
        Consumer<String, byte[]> consumer = mock(Consumer.class);
        when(consumer.assignment()).thenReturn(Set.of(LEASE_PARTITION, emptied));
        when(consumer.endOffsets(any())).thenReturn(Map.of(LEASE_PARTITION, 1L, emptied, 50L));
        when(consumer.position(LEASE_PARTITION)).thenReturn(1L);
        when(consumer.position(emptied)).thenReturn(50L);

        coordinator.onJobLease(leaseRecord(expired, 0), consumer);

        ArgumentCaptor<JobLease> captor = ArgumentCaptor.forClass(JobLease.class);
        verify(publisher).publishLease(captor.capture());
        assertEquals(8, captor.getValue().leaseEpoch());
        assertEquals("instance-a", captor.getValue().leaseOwnerInstance());
    }

    @Test
    void sameInstanceRestartHeartbeatsOwnedLeaseBeforeReplayCompletes() {
        stateStore.upsertDefinition(jobDefinition());
        stateStore.upsertDefinition(jobDefinition("job-2"));

        Instant now = Instant.now();
        // Owned, unexpired, inside the 10s heartbeat window of the 30s policy.
        JobLease owned = new JobLease("job-1", 1, "site-a", "instance-a", 40, LeaseStatus.RUNNING,
            now.plusSeconds(5), now.minusSeconds(25));
        // Expired and held by a peer: must NOT be claimed while replay is still in progress.
        JobLease peerExpired = new JobLease("job-2", 1, "site-b", "instance-b", 7, LeaseStatus.RUNNING,
            now.minusSeconds(5), now.minusSeconds(30));

        AtomicLong position = new AtomicLong(10);
        Consumer<String, byte[]> consumer = leaseConsumer(position, 1_000L);

        coordinator.onJobLeases(List.of(leaseRecord(owned, 8), leaseRecord(peerExpired, 9)), consumer);

        ArgumentCaptor<JobLease> captor = ArgumentCaptor.forClass(JobLease.class);
        verify(publisher, times(1)).publishLease(captor.capture());
        JobLease renewed = captor.getValue();
        assertEquals("job-1", renewed.jobId());
        assertEquals(40, renewed.leaseEpoch());
        assertEquals("instance-a", renewed.leaseOwnerInstance());
        assertEquals(LeaseStatus.RUNNING, renewed.status());
        assertTrue(renewed.leaseExpiresAt().isAfter(owned.leaseExpiresAt()));
        assertEquals("instance-b", stateStore.getLease("job-2").orElseThrow().leaseOwnerInstance());
        // No runner start, restart, or status probe while replay is in progress.
        verify(jobRunnerRegistry, never()).resolve(any());
    }

    @Test
    void scheduledReconcileHeartbeatsOwnedLeaseWhileReplayInProgress() {
        stateStore.upsertDefinition(jobDefinition());
        stateStore.upsertDefinition(jobDefinition("job-2"));

        Instant now = Instant.now();
        JobLease owned = new JobLease("job-1", 1, "site-a", "instance-a", 40, LeaseStatus.RUNNING,
            now.plusSeconds(5), now.minusSeconds(25));
        AtomicLong position = new AtomicLong(10);
        Consumer<String, byte[]> consumer = leaseConsumer(position, 1_000L);
        coordinator.onJobLease(leaseRecord(owned, 8), consumer);
        verify(publisher, times(1)).publishLease(any());

        coordinator.reconcileAll();

        // Second heartbeat is not due yet, and job-2 (no lease) is not claimed before catch-up.
        verify(publisher, times(1)).publishLease(any());
        assertTrue(stateStore.getLease("job-2").isEmpty());
        verify(jobRunnerRegistry, never()).resolve(any());
    }

    @SuppressWarnings("unchecked")
    private static Consumer<String, byte[]> leaseConsumer(AtomicLong position, long endOffset) {
        Consumer<String, byte[]> consumer = mock(Consumer.class);
        when(consumer.assignment()).thenReturn(Set.of(LEASE_PARTITION));
        when(consumer.endOffsets(any())).thenReturn(Map.of(LEASE_PARTITION, endOffset));
        when(consumer.position(LEASE_PARTITION)).thenAnswer(invocation -> position.get());
        return consumer;
    }

    private static ConsumerRecord<String, byte[]> leaseRecord(JobLease lease, long offset) {
        byte[] payload = OBJECT_MAPPER.writeValueAsBytes(lease);
        return new ConsumerRecord<>(TopicNames.JOB_LEASES, 0, offset, lease.jobId(), payload);
    }

    private static JobDefinition jobDefinition() {
        return jobDefinition("job-1");
    }

    private static JobDefinition jobDefinition(String jobId) {
        return new JobDefinition(
            jobId,
            1,
            JobType.ROUTE_APP,
            DesiredJobState.ACTIVE,
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
