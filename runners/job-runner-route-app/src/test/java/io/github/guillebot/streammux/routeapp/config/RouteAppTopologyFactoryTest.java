package io.github.guillebot.streammux.routeapp.config;

import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.PayloadFormat;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteAppTopologyFactoryTest {

    @Test
    void routesInputRecordsToAllMatchingOutputTopics() {
        JobDefinition definition = jobDefinition();
        RouteAppTopologyFactory factory = new RouteAppTopologyFactory();
        Topology topology = factory.build(definition);
        Properties properties = factory.properties(definition, 3);

        try (TopologyTestDriver driver = new TopologyTestDriver(topology, properties)) {
            TestInputTopic<String, byte[]> inputTopic = driver.createInputTopic(
                "input-topic",
                new StringSerializer(),
                new ByteArraySerializer()
            );
            TestOutputTopic<String, byte[]> alarmsTopic = driver.createOutputTopic(
                "alerts-topic",
                new StringDeserializer(),
                new ByteArrayDeserializer()
            );
            TestOutputTopic<String, byte[]> substringTopic = driver.createOutputTopic(
                "contains-topic",
                new StringDeserializer(),
                new ByteArrayDeserializer()
            );

            byte[] payload = """
                {"message":{"type":"ALARM"},"payload":"contains-bar"}
                """.getBytes(StandardCharsets.UTF_8);

            inputTopic.pipeInput("job-1", payload);

            assertArrayEquals(payload, alarmsTopic.readValue());
            assertArrayEquals(payload, substringTopic.readValue());
            assertTrue(alarmsTopic.isEmpty());
            assertTrue(substringTopic.isEmpty());
        }
    }

    @Test
    void buildsPropertiesWithLeaseScopedApplicationIdAndOverrides() {
        JobDefinition definition = jobDefinition();
        RouteAppTopologyFactory factory = new RouteAppTopologyFactory();

        Properties properties = factory.properties(definition, 9);

        assertEquals("job-1-9", properties.getProperty(StreamsConfig.APPLICATION_ID_CONFIG));
        assertEquals("kafka.example:9092", properties.getProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals("earliest", properties.getProperty(StreamsConfig.consumerPrefix("auto.offset.reset")));
    }

    private static JobDefinition jobDefinition() {
        return new JobDefinition(
            "job-1",
            1,
            JobType.ROUTE_APP,
            DesiredJobState.ACTIVE,
            1,
            "site-a",
            LeasePolicy.defaults(),
            1,
            new RouteAppConfig(
                "input-topic",
                PayloadFormat.JSON,
                PayloadFormat.JSON,
                null,
                List.of(
                    new RouteDefinition("route-1", "message.type == \"ALARM\"", "alerts-topic"),
                    new RouteDefinition("route-2", "contains-bar", "contains-topic")
                ),
                Map.of(
                    StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka.example:9092",
                    StreamsConfig.consumerPrefix("auto.offset.reset"), "earliest"
                ),
                Map.of()
            ),
            null,
            null,
            Map.of(),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }
}
