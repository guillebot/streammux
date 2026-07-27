package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.api.config.KafkaTopicProperties;
import io.github.guillebot.streammux.api.config.TopicValidationProperties;
import io.github.guillebot.streammux.contracts.model.TopicNames;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformSettingsServiceTest {
    @Test
    void snapshotReturnsCuratedNonSecretSettings() {
        KafkaTopicProperties topics = KafkaTopicProperties.defaults();
        TopicValidationProperties validation = new TopicValidationProperties(
            List.of("net.optimum.monitoring.example.in"),
            List.of("net.optimum.monitoring."),
            List.of(),
            List.of("lab.optimum.experimental.streamlens.streammux.")
        );
        PlatformSettingsService service = new PlatformSettingsService(
            "job-management-api",
            "kafka1:9092,kafka2:9092",
            "job-management-api-read-model-abc",
            "health,info",
            true,
            topics,
            validation
        );

        PlatformSettingsService.PlatformSettings settings = service.snapshot();

        assertNotNull(settings.loadedAt());
        assertEquals("job-management-api", settings.module().applicationName());
        assertEquals("kafka1:9092,kafka2:9092", settings.kafka().bootstrapServers());
        assertEquals("job-management-api-read-model-abc", settings.kafka().consumerGroupId());
        assertEquals(TopicNames.JOB_DEFINITIONS, settings.topics().jobDefinitions());
        assertEquals(TopicNames.JOB_COMMANDS, settings.topics().jobCommands());
        assertEquals(List.of("net.optimum.monitoring.example.in"), settings.validation().allowedInputTopics());
        assertEquals(List.of("net.optimum.monitoring."), settings.validation().allowedInputTopicPrefixes());
        assertEquals(List.of("health", "info"), settings.api().exposedActuatorEndpoints());
        assertTrue(settings.api().springdocShowActuator());
    }
}
