package io.github.guillebot.streammux.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaJobStateProjector {
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaJobStateProjector.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final JobStateStore stateStore;

    public KafkaJobStateProjector(JobStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @KafkaListener(topics = "${streammux.topics.job-definitions}")
    public void onJobDefinition(ConsumerRecord<String, byte[]> record) {
        String jobId = record.key();
        if (record.value() == null) {
            if (jobId != null) {
                stateStore.removeJob(jobId);
            }
            return;
        }

        JobDefinition definition = read(record.value(), JobDefinition.class);
        if (definition.desiredState() == DesiredJobState.DELETED) {
            stateStore.removeJob(definition.jobId());
            return;
        }
        stateStore.upsertDefinition(definition);
    }

    @KafkaListener(topics = "${streammux.topics.job-leases}")
    public void onJobLease(ConsumerRecord<String, byte[]> record) {
        if (record.value() == null) {
            return;
        }
        stateStore.upsertLease(read(record.value(), JobLease.class));
    }

    @KafkaListener(topics = "${streammux.topics.job-status}")
    public void onJobStatus(ConsumerRecord<String, byte[]> record) {
        if (record.value() == null) {
            return;
        }
        stateStore.upsertStatus(read(record.value(), JobRuntimeStatus.class));
    }

    @KafkaListener(topics = "${streammux.topics.job-events}")
    public void onJobEvent(ConsumerRecord<String, byte[]> record) {
        if (record.value() == null) {
            return;
        }
        stateStore.appendEvent(read(record.value(), JobEvent.class));
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
