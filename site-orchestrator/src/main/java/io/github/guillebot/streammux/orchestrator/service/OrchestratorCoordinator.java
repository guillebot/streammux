package io.github.guillebot.streammux.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.orchestrator.lease.LeaseManager;
import io.github.guillebot.streammux.orchestrator.metrics.StreammuxOrchestratorMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

@Component
public class OrchestratorCoordinator implements ConsumerSeekAware {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrchestratorCoordinator.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final OrchestratorStateStore stateStore;
    private final OrchestratorService orchestratorService;
    private final LeaseManager leaseManager;
    private final KafkaOrchestratorPublisher publisher;
    private final StreammuxOrchestratorMetrics orchestratorMetrics;
    private final Set<TopicPartition> bootstrappedPartitions = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> lastUnhealthyWarnAt = new ConcurrentHashMap<>();

    public OrchestratorCoordinator(
        OrchestratorStateStore stateStore,
        OrchestratorService orchestratorService,
        LeaseManager leaseManager,
        KafkaOrchestratorPublisher publisher,
        StreammuxOrchestratorMetrics orchestratorMetrics
    ) {
        this.stateStore = stateStore;
        this.orchestratorService = orchestratorService;
        this.leaseManager = leaseManager;
        this.publisher = publisher;
        this.orchestratorMetrics = orchestratorMetrics;
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        for (TopicPartition partition : assignments.keySet()) {
            if (bootstrappedPartitions.add(partition)) {
                LOGGER.info("Seeking to beginning of compacted topic {} after orchestrator startup", partition.topic());
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
            reconcile(definition.jobId(), false);
            stateStore.removeDefinition(definition.jobId());
            stateStore.removeLease(definition.jobId());
            return;
        }
        stateStore.upsertDefinition(definition);
        reconcile(definition.jobId(), false);
    }

    @KafkaListener(topics = "${streammux.topics.job-leases}")
    public void onJobLease(ConsumerRecord<String, byte[]> record) {
        String jobId = record.key();
        if (record.value() == null) {
            if (jobId != null) {
                stateStore.removeLease(jobId);
                reconcile(jobId, false);
            }
            return;
        }

        JobLease lease = read(record.value(), JobLease.class);
        orchestratorService.observeLease(lease);
        stateStore.upsertLease(lease);
        reconcile(lease.jobId(), true);
    }

    @Scheduled(fixedDelayString = "${streammux.orchestrator.reconcile-interval-ms:5000}")
    public void reconcileAll() {
        orchestratorMetrics.recordReconcile();
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
