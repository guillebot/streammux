package io.github.guillebot.streammux.orchestrator.service;

import tools.jackson.databind.ObjectMapper;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.orchestrator.config.KafkaTopicProperties;
import io.github.guillebot.streammux.orchestrator.lease.LeaseManager;
import io.github.guillebot.streammux.orchestrator.metrics.StreammuxOrchestratorMetrics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.event.ListenerContainerIdleEvent;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Instant;
import java.util.stream.Collectors;

@Component
public class OrchestratorCoordinator implements ConsumerSeekAware {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrchestratorCoordinator.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OrchestratorStateStore stateStore;
    private final OrchestratorService orchestratorService;
    private final LeaseManager leaseManager;
    private final KafkaOrchestratorPublisher publisher;
    private final StreammuxOrchestratorMetrics orchestratorMetrics;
    private final KafkaTopicProperties topics;
    private final Set<TopicPartition> bootstrappedPartitions = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> lastUnhealthyWarnAt = new ConcurrentHashMap<>();
    private final AtomicBoolean leaseLogReady = new AtomicBoolean(false);

    public OrchestratorCoordinator(
        OrchestratorStateStore stateStore,
        OrchestratorService orchestratorService,
        LeaseManager leaseManager,
        KafkaOrchestratorPublisher publisher,
        StreammuxOrchestratorMetrics orchestratorMetrics
    ) {
        this(stateStore, orchestratorService, leaseManager, publisher, orchestratorMetrics, KafkaTopicProperties.defaults());
    }

    @Autowired
    public OrchestratorCoordinator(
        OrchestratorStateStore stateStore,
        OrchestratorService orchestratorService,
        LeaseManager leaseManager,
        KafkaOrchestratorPublisher publisher,
        StreammuxOrchestratorMetrics orchestratorMetrics,
        KafkaTopicProperties topics
    ) {
        this.stateStore = stateStore;
        this.orchestratorService = orchestratorService;
        this.leaseManager = leaseManager;
        this.publisher = publisher;
        this.orchestratorMetrics = orchestratorMetrics;
        this.topics = topics;
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        for (Map.Entry<TopicPartition, Long> entry : assignments.entrySet()) {
            TopicPartition partition = entry.getKey();
            Long offset = entry.getValue();
            boolean firstAssignmentThisProcess = bootstrappedPartitions.add(partition);
            if (!firstAssignmentThisProcess) {
                LOGGER.info("Resuming {}-{} at offset {} after rebalance (committed offsets retained)",
                    partition.topic(), partition.partition(), offset);
                continue;
            }
            if (offset == null || offset < 0) {
                LOGGER.info("No committed offset for {}-{}; seeking to beginning",
                    partition.topic(), partition.partition());
                callback.seekToBeginning(partition.topic(), partition.partition());
            } else {
                LOGGER.info(
                    "Seeking compacted {}-{} to beginning on first assignment to rebuild in-memory state (committed offset was {})",
                    partition.topic(),
                    partition.partition(),
                    offset
                );
                callback.seekToBeginning(partition.topic(), partition.partition());
            }
        }
    }

    @KafkaListener(topics = "${streammux.topics.job-definitions}")
    public void onJobDefinition(ConsumerRecord<String, byte[]> record) {
        String jobId = record.key();
        if (record.value() == null) {
            if (jobId != null) {
                stateStore.removeDefinition(jobId);
            }
            return;
        }

        JobDefinition definition = read(record.value(), JobDefinition.class);
        if (definition.desiredState() == DesiredJobState.DELETED) {
            stateStore.upsertDefinition(definition);
            if (leaseLogReady.get()) {
                reconcile(definition.jobId(), false);
            }
            stateStore.removeDefinition(definition.jobId());
            stateStore.removeLease(definition.jobId());
            return;
        }
        stateStore.upsertDefinition(definition);
        if (leaseLogReady.get()) {
            reconcile(definition.jobId(), false);
        }
    }

    /**
     * Consumes the compacted lease log one poll batch at a time.
     *
     * <p>The whole batch is ingested before catch-up is evaluated, so {@code consumer.position()}
     * (which already sits at the end of the batch) is only compared against the high watermark
     * once every record it accounts for is in the state store. A record listener could not tell
     * "more records of this batch still pending" from "offsets compacted away", which is what made
     * the previous per-record high-watermark gate spin without ever completing.
     */
    @KafkaListener(topics = "${streammux.topics.job-leases}", batch = "true")
    public void onJobLeases(List<ConsumerRecord<String, byte[]>> records, Consumer<?, ?> consumer) {
        Set<String> jobIds = new LinkedHashSet<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            try {
                ingestLeaseRecord(record);
            } catch (RuntimeException ex) {
                throw new BatchListenerFailedException("Failed to ingest lease record", ex, record);
            }
            if (record.key() != null) {
                jobIds.add(record.key());
            }
        }
        if (!leaseLogReady.get()) {
            heartbeatOwnedLeases(jobIds);
            tryCompleteLeaseReplay(consumer);
            return;
        }
        for (String jobId : jobIds) {
            reconcile(jobId, true);
        }
    }

    /** Single-record entry point for tests and in-process callers; delivers a one-record batch. */
    public void onJobLease(ConsumerRecord<String, byte[]> record, Consumer<?, ?> consumer) {
        onJobLeases(List.of(record), consumer);
    }

    @EventListener
    public void onListenerIdle(ListenerContainerIdleEvent event) {
        if (leaseLogReady.get() || event.getConsumer() == null) {
            return;
        }
        tryCompleteLeaseReplay(event.getConsumer());
    }

    @Scheduled(fixedDelayString = "${streammux.orchestrator.reconcile-interval-ms:5000}")
    public void reconcileAll() {
        if (!leaseLogReady.get()) {
            LOGGER.debug("Lease log catch-up in progress; heartbeating owned leases only");
            heartbeatOwnedLeases(stateStore.listDefinitions().stream()
                .map(JobDefinition::jobId)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
            return;
        }
        orchestratorMetrics.recordReconcile();
        for (JobDefinition definition : stateStore.listDefinitions()) {
            reconcile(definition.jobId(), false);
            JobLease lease = stateStore.getLease(definition.jobId()).orElse(null);
            orchestratorService.recoverOwnedRunnerIfMissing(definition, lease);
        }
    }

    /**
     * Keeps leases this instance already holds alive while the compacted log is still replaying.
     * Only a same-epoch renew of an unexpired, owned lease is published: it can never take a lease
     * from a peer, and a higher own epoch later in the log supersedes it on ingest. No CLAIM, no
     * runner start, no RELEASE happens here; those wait for catch-up.
     */
    private void heartbeatOwnedLeases(Set<String> jobIds) {
        for (String jobId : jobIds) {
            JobDefinition definition = stateStore.getDefinition(jobId).orElse(null);
            JobLease currentLease = stateStore.getLease(jobId).orElse(null);
            if (definition == null || currentLease == null) {
                continue;
            }
            JobLease renewed = orchestratorService.renewOwnedUnexpiredLease(definition, currentLease);
            if (renewed == null || Objects.equals(renewed, currentLease)) {
                continue;
            }
            stateStore.upsertLease(renewed);
            if (orchestratorService.shouldPublishLease(renewed)) {
                LOGGER.info(
                    "Heartbeated owned lease for {} at epoch {} during lease log catch-up",
                    jobId,
                    renewed.leaseEpoch()
                );
                publisher.publishLease(renewed);
            }
        }
    }

    private void ingestLeaseRecord(ConsumerRecord<String, byte[]> record) {
        String jobId = record.key();
        if (record.value() == null) {
            if (jobId != null && leaseLogReady.get()) {
                stateStore.removeLease(jobId);
            }
            return;
        }

        JobLease lease = read(record.value(), JobLease.class);
        orchestratorService.observeLease(lease);
        if (!stateStore.upsertLease(lease)) {
            LOGGER.debug(
                "Kept higher-epoch lease for {} while ingesting epoch {}",
                lease.jobId(),
                lease.leaseEpoch()
            );
        }
    }

    /**
     * Completes catch-up once the consumer position of every assigned lease partition has reached
     * its high watermark. Called after a full poll batch has been ingested (or on idle), so
     * position never runs ahead of the state store; compacted-away offsets and control records
     * between the last delivered record and the high watermark do not block completion.
     * One {@code endOffsets} round trip per batch, not per record.
     */
    private void tryCompleteLeaseReplay(Consumer<?, ?> consumer) {
        if (leaseLogReady.get() || consumer == null) {
            return;
        }
        Set<TopicPartition> leasePartitions = consumer.assignment().stream()
            .filter(partition -> topics.jobLeases().equals(partition.topic()))
            .collect(Collectors.toCollection(HashSet::new));
        if (leasePartitions.isEmpty()) {
            return;
        }
        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(leasePartitions);
        for (TopicPartition partition : leasePartitions) {
            long end = endOffsets.getOrDefault(partition, 0L);
            long position = consumer.position(partition);
            if (position < end) {
                LOGGER.debug("Lease log catch-up in progress for {}: position {} < end {}", partition, position, end);
                return;
            }
        }
        if (!leaseLogReady.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info("Compacted lease log catch-up complete; reconciling from local state without regressive claims");
        for (JobDefinition definition : stateStore.listDefinitions()) {
            reconcile(definition.jobId(), false);
            JobLease lease = stateStore.getLease(definition.jobId()).orElse(null);
            orchestratorService.recoverOwnedRunnerIfMissing(definition, lease);
        }
    }

    private void reconcile(String jobId, boolean allowRunnerStart) {
        stateStore.getDefinition(jobId).ifPresent(definition -> {
            JobLease currentLease = stateStore.getLease(jobId).orElse(null);
            JobLease updatedLease = orchestratorService.reconcile(definition, currentLease);

            if (updatedLease == null) {
                stateStore.removeLease(jobId);
            } else if (shouldUpsertLease(currentLease, updatedLease)) {
                stateStore.upsertLease(updatedLease);
                if (!Objects.equals(updatedLease, currentLease) && orchestratorService.shouldPublishLease(updatedLease)) {
                    publisher.publishLease(updatedLease);
                } else if (!Objects.equals(updatedLease, currentLease) && !orchestratorService.shouldPublishLease(updatedLease)) {
                    LOGGER.warn(
                        "Skipped publishing regressive lease for {} at epoch {}",
                        jobId,
                        updatedLease.leaseEpoch()
                    );
                }
            }

            if (allowRunnerStart) {
                JobLease authoritativeLease = stateStore.getLease(jobId).orElse(null);
                orchestratorService.maybeStartConfirmedRunner(definition, authoritativeLease);
            }

            // Only the lease owner should publish job-status. Non-owners have no local runner and would
            // emit STOPPED on every reconcile, causing last-write-wins flapping in the API when multiple
            // orchestrators use different Kafka consumer groups (e.g. distinct STREAMMUX_INSTANCE_ID).
            JobLease leaseForStatus = stateStore.getLease(jobId).orElse(null);
            if (leaseForStatus != null && leaseManager.ownsLease(leaseForStatus)) {
                orchestratorService.maybeRestartFailedRunner(definition, leaseForStatus);
            }

            JobRuntimeStatus status = orchestratorService.status(definition.jobId(), definition);
            if (status != null && status.health() == HealthState.UNHEALTHY
                && leaseForStatus != null && leaseManager.ownsLease(leaseForStatus)) {
                Instant now = Instant.now();
                Instant lastWarn = lastUnhealthyWarnAt.get(jobId);
                if (lastWarn == null || now.isAfter(lastWarn.plusSeconds(60))) {
                    LOGGER.warn(
                        "Job {} remains UNHEALTHY on this instance: {}",
                        jobId,
                        status.failureReason() != null ? status.failureReason() : "no failure reason"
                    );
                    lastUnhealthyWarnAt.put(jobId, now);
                }
            } else {
                lastUnhealthyWarnAt.remove(jobId);
            }
            if (status != null && shouldPublishRuntimeStatus(leaseForStatus)) {
                publisher.publishStatus(status);
            }
        });
    }

    private static boolean shouldUpsertLease(JobLease currentLease, JobLease updatedLease) {
        if (updatedLease == null) {
            return false;
        }
        if (currentLease == null) {
            return true;
        }
        return updatedLease.leaseEpoch() >= currentLease.leaseEpoch();
    }

    /**
     * Publish after release ({@code leaseForStatus == null}) or while this site/instance holds the lease.
     */
    private boolean shouldPublishRuntimeStatus(JobLease leaseForStatus) {
        return leaseForStatus == null || leaseManager.ownsLease(leaseForStatus);
    }

    private <T> T read(byte[] payload, Class<T> type) {
        try {
            return OBJECT_MAPPER.readValue(payload, type);
        } catch (Exception ex) {
            LOGGER.error("Failed to deserialize {}", type.getSimpleName(), ex);
            throw new IllegalStateException("Failed to deserialize " + type.getSimpleName(), ex);
        }
    }
}
