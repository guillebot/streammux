package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.api.config.KafkaTopicProperties;
import io.github.guillebot.streammux.contracts.command.JobCommand;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaJobCommandPublisher implements JobCommandPublisher {
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaTopicProperties topics;

    public KafkaJobCommandPublisher(KafkaTemplate<String, Object> kafkaTemplate, KafkaTopicProperties topics) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
    }

    @Override public void publishDefinition(JobDefinition definition) { kafkaTemplate.send(topics.jobDefinitions(), definition.jobId(), definition); }
    @Override public void publishCommand(JobCommand command) { kafkaTemplate.send(topics.jobCommands(), command.jobId(), command); }
    @Override public void publishEvent(JobEvent event) { kafkaTemplate.send(topics.jobEvents(), event.jobId(), event); }
}
