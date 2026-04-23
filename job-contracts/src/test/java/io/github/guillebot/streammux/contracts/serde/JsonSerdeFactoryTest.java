package io.github.guillebot.streammux.contracts.serde;

import io.github.guillebot.streammux.contracts.command.JobCommand;
import io.github.guillebot.streammux.contracts.config.AlarmsToZtrConfig;
import io.github.guillebot.streammux.contracts.config.AlarmsToZtrFilter;
import io.github.guillebot.streammux.contracts.config.AlarmsToZtrFilterRule;
import io.github.guillebot.streammux.contracts.config.RandomSamplerConfig;
import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.CommandType;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.PayloadFormat;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;
import org.apache.kafka.common.serialization.Serde;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonSerdeFactoryTest {

    @Test
    void roundTripsJobDefinitionWithJavaTimeAndNestedConfig() {
        JobDefinition definition = new JobDefinition(
            "job-1",
            7,
            JobType.ROUTE_APP,
            DesiredJobState.ACTIVE,
            9,
            "site-a",
            LeasePolicy.defaults(),
            2,
            new RouteAppConfig(
                "input-topic",
                PayloadFormat.JSON,
                PayloadFormat.JSON,
                null,
                List.of(new RouteDefinition("route-1", "message.type == \"ALARM\"", "alerts")),
                Map.of("bootstrap.servers", "kafka:9092"),
                Map.of("format", "json")
            ),
            null,
            null,
            Map.of("team", "mux"),
            List.of("critical", "json"),
            Instant.parse("2024-01-02T03:04:05Z"),
            "tester"
        );

        Serde<JobDefinition> serde = JsonSerdeFactory.jsonSerde(JobDefinition.class);

        JobDefinition restored = serde.deserializer().deserialize("job-definitions", serde.serializer().serialize("job-definitions", definition));

        assertEquals(definition, restored);
    }

    @Test
    void roundTripsJobDefinitionRandomSampler() {
        JobDefinition definition = new JobDefinition(
            "job-sampler",
            1,
            JobType.RANDOM_SAMPLER,
            DesiredJobState.ACTIVE,
            1,
            "site-a",
            LeasePolicy.defaults(),
            1,
            null,
            new RandomSamplerConfig("in", "out", 0.25d, Map.of("bootstrap.servers", "kafka:9092")),
            null,
            Map.of(),
            List.of(),
            Instant.parse("2024-01-02T03:04:05Z"),
            "tester"
        );

        Serde<JobDefinition> serde = JsonSerdeFactory.jsonSerde(JobDefinition.class);

        JobDefinition restored = serde.deserializer().deserialize("job-definitions", serde.serializer().serialize("job-definitions", definition));

        assertEquals(definition, restored);
    }

    @Test
    void roundTripsJobDefinitionAlarmsToZtr() {
        AlarmsToZtrConfig alarmsToZtrConfig = new AlarmsToZtrConfig(
            "raw-alarms",
            "ztr-alarms",
            "nokia",
            1.0d,
            Map.of(
                "default", Map.of(
                    "event_id", "$input.alarm.id",
                    "event_type", Map.of(
                        "$input", "alarm.severity",
                        "$map", Map.of("CLEARED", "CLEAR", "default", "NEW")
                    ),
                    "labels", List.of("$input.extension.source", "prod")
                )
            ),
            "default",
            new AlarmsToZtrFilter(
                "default",
                List.of(new AlarmsToZtrFilterRule("alarm.severity", "in", null, List.of("CRITICAL", "MAJOR"), null))
            ),
            Map.of("bootstrap.servers", "kafka:9092")
        );
        JobDefinition definition = new JobDefinition(
            "job-alarms",
            3,
            JobType.ALARMS_TO_ZTR,
            DesiredJobState.ACTIVE,
            2,
            "site-a",
            LeasePolicy.defaults(),
            1,
            null,
            null,
            alarmsToZtrConfig,
            Map.of(),
            List.of(),
            Instant.parse("2024-01-02T03:04:05Z"),
            "tester"
        );

        Serde<JobDefinition> serde = JsonSerdeFactory.jsonSerde(JobDefinition.class);

        JobDefinition restored = serde.deserializer().deserialize("job-definitions", serde.serializer().serialize("job-definitions", definition));

        assertEquals(definition, restored);
    }

    @Test
    void roundTripsJobCommand() {
        JobCommand command = new JobCommand(
            "cmd-1",
            "job-1",
            4,
            CommandType.RESTART,
            Instant.parse("2024-01-02T03:04:05Z"),
            "api",
            Map.of("reason", "integration-test")
        );

        Serde<JobCommand> serde = JsonSerdeFactory.jsonSerde(JobCommand.class);

        JobCommand restored = serde.deserializer().deserialize("job-commands", serde.serializer().serialize("job-commands", command));

        assertEquals(command, restored);
    }

    @Test
    void roundTripsJobEvent() {
        JobEvent event = new JobEvent(
            "evt-1",
            "job-1",
            4,
            EventType.STARTED,
            Instant.parse("2024-01-02T03:04:05Z"),
            "site-a",
            "orchestrator-1",
            "Job started",
            Map.of("attempt", 1, "source", "test")
        );

        Serde<JobEvent> serde = JsonSerdeFactory.jsonSerde(JobEvent.class);

        JobEvent restored = serde.deserializer().deserialize("job-events", serde.serializer().serialize("job-events", event));

        assertEquals(event, restored);
    }

    @Test
    void returnsNullWhenDeserializingNullPayload() {
        Serde<JobDefinition> serde = JsonSerdeFactory.jsonSerde(JobDefinition.class);

        assertNull(serde.deserializer().deserialize("job-definitions", null));
    }
}
