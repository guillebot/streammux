package io.github.guillebot.streammux.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.orchestrator.lease.LeaseManager;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class OrchestratorCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrchestratorCoordinator.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final OrchestratorStateStore stateStore;
    private final OrchestratorService orchestratorService;
    private final LeaseManager leaseManager;
    private final KafkaOrchestratorPublisher publisher;

    public OrchestratorCoordinator(
        OrchestratorStateStore stateStore,
        OrchestratorService orchestratorService,
        LeaseManager leaseManager,
        KafkaOrchestratorPublisher publisher
    ) {
        this.stateStore = stateStore;
        this.orchestratorService = orchestratorService;
        this.leaseManager = leaseManager;
        this.publisher = publisher;
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
            reconcile(definition.jobId());
            stateStore.removeDefinition(definition.jobId());
            stateStore.removeLease(definition.jobId());
            return;
        }
        stateStore.upsertDefinition(definition);
        reconcile(definition.jobId());
    }

    @KafkaListener(topics = "${streammux.topics.job-leases}")
    public void onJobLease(ConsumerRecord<String, byte[]> record) {
        String jobId = record.key();
        if (record.value() == null) {
            if (jobId != null) {
                stateStore.removeLease(jobId);
                reconcile(jobId);
            }
            return;
        }

        JobLease lease = read(record.value(), JobLease.class);
        stateStore.upsertLease(lease);
        reconcile(lease.jobId());
    }

    @Scheduled(fixedDelayString = "${streammux.orchestrator.reconcile-interval-ms:5000}")
    public void reconcileAll() {
        for (JobDefinition definition : stateStore.listDefinitions()) {
            reconcile(definition.jobId());
        }
    }

    private void reconcile(String jobId) {
        stateStore.getDefinition(jobId).ifPresent(definition -> {
            JobLease currentLease = stateStore.getLease(jobId).orElse(null);
            JobLease updatedLease = orchestratorService.reconcile(definition, currentLease);

            if (updatedLease == null) {
                stateStore.removeLease(jobId);
            } else {
                stateStore.upsertLease(updatedLease);
                if (!Objects.equals(updatedLease, currentLease)) {
                    publisher.publishLease(updatedLease);
                }
            }

            // Only the lease owner should publish job-status. Non-owners have no local runner and would
            // emit STOPPED on every reconcile, causing last-write-wins flapping in the API when multiple
            // orchestrators use different Kafka consumer groups (e.g. distinct STREAMMUX_INSTANCE_ID).
            JobRuntimeStatus status = orchestratorService.status(definition.jobId(), definition);
            if (status != null && shouldPublishRuntimeStatus(updatedLease)) {
                publisher.publishStatus(status);
            }
        });
    }

    /**
     * Publish after release ({@code updatedLease == null}) or while this site/instance holds the lease.
     */
    private boolean shouldPublishRuntimeStatus(JobLease updatedLease) {
        return updatedLease == null || leaseManager.ownsLease(updatedLease);
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
