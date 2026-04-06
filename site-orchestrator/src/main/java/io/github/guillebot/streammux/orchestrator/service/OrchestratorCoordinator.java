package io.github.guillebot.streammux.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
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
    private final KafkaOrchestratorPublisher publisher;

    public OrchestratorCoordinator(
        OrchestratorStateStore stateStore,
        OrchestratorService orchestratorService,
        KafkaOrchestratorPublisher publisher
    ) {
        this.stateStore = stateStore;
        this.orchestratorService = orchestratorService;
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

            JobRuntimeStatus status = orchestratorService.status(definition.jobId(), definition);
            if (status != null) {
                publisher.publishStatus(status);
            }
        });
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
