package io.github.guillebot.streammux.orchestrator.config;

import io.github.guillebot.streammux.contracts.model.TopicNames;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "streammux.topics")
public record KafkaTopicProperties(String jobDefinitions, String jobLeases, String jobStatus, String jobEvents, String jobCommands) {
    public static KafkaTopicProperties defaults() {
        return new KafkaTopicProperties(TopicNames.JOB_DEFINITIONS, TopicNames.JOB_LEASES, TopicNames.JOB_STATUS, TopicNames.JOB_EVENTS, TopicNames.JOB_COMMANDS);
    }
}
