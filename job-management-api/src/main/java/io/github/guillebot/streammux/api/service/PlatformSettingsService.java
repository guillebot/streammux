package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.api.config.KafkaTopicProperties;
import io.github.guillebot.streammux.api.config.TopicValidationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Service
public class PlatformSettingsService {
    private final String applicationName;
    private final String bootstrapServers;
    private final String consumerGroupId;
    private final List<String> exposedActuatorEndpoints;
    private final boolean springdocShowActuator;
    private final KafkaTopicProperties topicProperties;
    private final TopicValidationProperties validationProperties;

    public PlatformSettingsService(
        @Value("${spring.application.name}") String applicationName,
        @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
        @Value("${spring.kafka.consumer.group-id}") String consumerGroupId,
        @Value("${management.endpoints.web.exposure.include:health}") String exposedActuatorEndpoints,
        @Value("${springdoc.show-actuator:false}") boolean springdocShowActuator,
        KafkaTopicProperties topicProperties,
        TopicValidationProperties validationProperties
    ) {
        this.applicationName = applicationName;
        this.bootstrapServers = bootstrapServers;
        this.consumerGroupId = consumerGroupId;
        this.exposedActuatorEndpoints = parseCsvList(exposedActuatorEndpoints);
        this.springdocShowActuator = springdocShowActuator;
        this.topicProperties = topicProperties;
        this.validationProperties = validationProperties;
    }

    public PlatformSettings snapshot() {
        return new PlatformSettings(
            Instant.now(),
            new ModuleSettings(applicationName),
            new KafkaSettings(bootstrapServers, consumerGroupId),
            new TopicSettings(
                topicProperties.jobDefinitions(),
                topicProperties.jobLeases(),
                topicProperties.jobStatus(),
                topicProperties.jobEvents(),
                topicProperties.jobCommands()
            ),
            new TopicValidationSettings(
                validationProperties.allowedInputTopics(),
                validationProperties.allowedInputTopicPrefixes(),
                validationProperties.allowedOutputTopics(),
                validationProperties.allowedOutputTopicPrefixes()
            ),
            new ApiSettings(exposedActuatorEndpoints, springdocShowActuator)
        );
    }

    private static List<String> parseCsvList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    public record PlatformSettings(
        Instant loadedAt,
        ModuleSettings module,
        KafkaSettings kafka,
        TopicSettings topics,
        TopicValidationSettings validation,
        ApiSettings api
    ) {}

    public record ModuleSettings(String applicationName) {}

    public record KafkaSettings(String bootstrapServers, String consumerGroupId) {}

    public record TopicSettings(
        String jobDefinitions,
        String jobLeases,
        String jobStatus,
        String jobEvents,
        String jobCommands
    ) {}

    public record TopicValidationSettings(
        List<String> allowedInputTopics,
        List<String> allowedInputTopicPrefixes,
        List<String> allowedOutputTopics,
        List<String> allowedOutputTopicPrefixes
    ) {}

    public record ApiSettings(List<String> exposedActuatorEndpoints, boolean springdocShowActuator) {}
}
