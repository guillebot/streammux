package io.github.guillebot.streammux.orchestrator.service;

import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.orchestrator.config.KafkaTopicProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaOrchestratorPublisher {
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaTopicProperties topics;

    public KafkaOrchestratorPublisher(KafkaTemplate<String, Object> kafkaTemplate, KafkaTopicProperties topics) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
    }

    public void publishLease(JobLease lease) {
        kafkaTemplate.send(topics.jobLeases(), lease.jobId(), lease);
    }

    public void publishStatus(JobRuntimeStatus status) {
        kafkaTemplate.send(topics.jobStatus(), status.jobId(), status);
    }
}
