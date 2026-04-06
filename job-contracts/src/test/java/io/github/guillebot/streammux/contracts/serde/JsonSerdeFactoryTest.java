package io.github.guillebot.streammux.contracts.serde;

import io.github.guillebot.streammux.contracts.command.JobCommand;
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
