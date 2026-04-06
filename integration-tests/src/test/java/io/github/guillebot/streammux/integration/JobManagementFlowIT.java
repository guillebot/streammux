package io.github.guillebot.streammux.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.guillebot.streammux.api.config.KafkaTopicProperties;
import io.github.guillebot.streammux.api.config.TopicValidationProperties;
import io.github.guillebot.streammux.api.service.JobService;
import io.github.guillebot.streammux.api.service.JobStateStore;
import io.github.guillebot.streammux.api.service.KafkaJobCommandPublisher;
import io.github.guillebot.streammux.api.service.KafkaJobStateProjector;
import io.github.guillebot.streammux.contracts.command.JobCommand;
import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.CommandType;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LagMetrics;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.PayloadFormat;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;
import io.github.guillebot.streammux.contracts.model.RuntimeState;
import io.github.guillebot.streammux.contracts.model.WorkerMetadata;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobManagementFlowIT extends KafkaIntegrationSupport {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void publishesApiChangesToKafkaAndProjectsThemBackIntoState() throws Exception {
        String prefix = "api-it-" + UUID.randomUUID();
        KafkaTopicProperties topics = new KafkaTopicProperties(
            prefix + "-definitions",
            prefix + "-leases",
            prefix + "-status",
            prefix + "-events",
            prefix + "-commands"
        );
        createTopics(List.of(
            topics.jobDefinitions(),
            topics.jobLeases(),
            topics.jobStatus(),
            topics.jobEvents(),
            topics.jobCommands()
        ));

        KafkaConsumer<String, byte[]> definitionConsumer = createConsumer(topics.jobDefinitions());
        KafkaConsumer<String, byte[]> eventConsumer = createConsumer(topics.jobEvents());
        KafkaConsumer<String, byte[]> commandConsumer = createConsumer(topics.jobCommands());
        KafkaConsumer<String, byte[]> statusConsumer = createConsumer(topics.jobStatus());

        KafkaTemplate<String, Object> kafkaTemplate = kafkaTemplate();
        JobStateStore apiStateStore = new JobStateStore();
        JobStateStore projectedStateStore = new JobStateStore();
        JobService service = new JobService(
            apiStateStore,
            new KafkaJobCommandPublisher(kafkaTemplate, topics),
            new TopicValidationProperties(List.of(), List.of(), List.of(), List.of())
        );
        KafkaJobStateProjector projector = new KafkaJobStateProjector(projectedStateStore);

        JobDefinition created = service.createJob(jobDefinition("job-1", DesiredJobState.ACTIVE, 1));
        projector.onJobDefinition(pollSingleRecord(definitionConsumer));
        projector.onJobEvent(pollSingleRecord(eventConsumer));

        assertEquals(created, projectedStateStore.getJob("job-1").orElseThrow());
        assertEquals(EventType.CREATED, projectedStateStore.getEvents("job-1").getFirst().eventType());

        service.issueCommand("job-1", CommandType.RESTART);
        JobCommand command = OBJECT_MAPPER.readValue(pollSingleRecord(commandConsumer).value(), JobCommand.class);
        JobEvent commandEvent = OBJECT_MAPPER.readValue(pollSingleRecord(eventConsumer).value(), JobEvent.class);
        projector.onJobEvent(record(topics.jobEvents(), command.jobId(), commandEvent));

        assertEquals(CommandType.RESTART, command.commandType());
        assertEquals(EventType.STARTED, projectedStateStore.getEvents("job-1").getLast().eventType());

        JobRuntimeStatus status = new JobRuntimeStatus(
            "job-1",
            1,
            RuntimeState.RUNNING,
            HealthState.HEALTHY,
            Instant.parse("2024-01-01T00:00:10Z"),
            new WorkerMetadata("worker-1", "route-app", "RUNNING", Map.of()),
            null,
            new LagMetrics(0, 1, 1)
        );
        kafkaTemplate.send(topics.jobStatus(), "job-1", status).get();
        projector.onJobStatus(pollSingleRecord(statusConsumer));

        assertEquals(status, projectedStateStore.getStatus("job-1").orElseThrow());

        JobDefinition deleted = jobDefinition("job-1", DesiredJobState.DELETED, created.jobVersion() + 1);
        kafkaTemplate.send(topics.jobDefinitions(), "job-1", deleted).get();
        projector.onJobDefinition(pollSingleRecord(definitionConsumer));

        assertTrue(projectedStateStore.getJob("job-1").isEmpty());
    }

    private KafkaTemplate<String, Object> kafkaTemplate() {
        DefaultKafkaProducerFactory<String, Object> producerFactory = new DefaultKafkaProducerFactory<>(Map.of(
            org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
            org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class,
            org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
            JsonSerializer.ADD_TYPE_INFO_HEADERS, false
        ));
        return new KafkaTemplate<>(producerFactory);
    }

    private static org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]> record(String topic, String key, Object value) throws Exception {
        return new org.apache.kafka.clients.consumer.ConsumerRecord<>(topic, 0, 0L, key, OBJECT_MAPPER.writeValueAsBytes(value));
    }

    private static JobDefinition jobDefinition(String jobId, DesiredJobState desiredState, long version) {
        return new JobDefinition(
            jobId,
            version,
            JobType.ROUTE_APP,
            desiredState,
            1,
            "site-a",
            new LeasePolicy(1, 1, 100, true),
            1,
            new RouteAppConfig(
                "input-topic",
                PayloadFormat.JSON,
                PayloadFormat.JSON,
                null,
                List.of(new RouteDefinition("route-1", "message.type == \"ALARM\"", "alerts")),
                Map.of(),
                Map.of()
            ),
            Map.of("team", "mux"),
            List.of("integration"),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }
}
