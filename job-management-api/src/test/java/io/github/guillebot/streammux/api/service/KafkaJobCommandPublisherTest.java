package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.api.config.KafkaTopicProperties;
import io.github.guillebot.streammux.contracts.command.JobCommand;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.CommandType;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaJobCommandPublisherTest {

    @Test
    void sendsDefinitionsCommandsAndEventsToConfiguredTopics() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        KafkaJobCommandPublisher publisher = new KafkaJobCommandPublisher(
            kafkaTemplate,
            new KafkaTopicProperties("defs", "leases", "status", "events", "commands")
        );

        JobDefinition definition = new JobDefinition("job-1", 1, JobType.ROUTE_APP, null, 1, "site-a", LeasePolicy.defaults(), 1, null, null, null, Map.of(), List.of(), Instant.parse("2024-01-01T00:00:00Z"), "tester");
        JobCommand command = new JobCommand("cmd-1", "job-1", 1, CommandType.RESTART, Instant.parse("2024-01-01T00:00:00Z"), "api", Map.of());
        JobEvent event = new JobEvent("evt-1", "job-1", 1, EventType.UPDATED, Instant.parse("2024-01-01T00:00:00Z"), null, "api", "updated", Map.of());

        publisher.publishDefinition(definition);
        publisher.publishCommand(command);
        publisher.publishEvent(event);

        verify(kafkaTemplate).send("defs", "job-1", definition);
        verify(kafkaTemplate).send("commands", "job-1", command);
        verify(kafkaTemplate).send("events", "job-1", event);
    }
}
