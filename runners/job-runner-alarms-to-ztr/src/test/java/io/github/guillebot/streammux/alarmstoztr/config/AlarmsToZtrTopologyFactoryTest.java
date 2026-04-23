package io.github.guillebot.streammux.alarmstoztr.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.guillebot.streammux.contracts.config.AlarmsToZtrConfig;
import io.github.guillebot.streammux.contracts.config.AlarmsToZtrFilter;
import io.github.guillebot.streammux.contracts.config.AlarmsToZtrFilterRule;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serdes;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlarmsToZtrTopologyFactoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void singleMappingTransformsInputToOutput() throws Exception {
        AlarmsToZtrConfig config = new AlarmsToZtrConfig(
            "raw-alarms",
            "ztr-alarms",
            "nokia",
            1.0d,
            Map.of("default", Map.of(
                "event_id", "$input.alarm.id",
                "event_type", Map.of("$input", "alarm.severity", "$map", Map.of("CLEARED", "CLEAR", "default", "NEW")),
                "environment", "prod"
            )),
            "default",
            null,
            Map.of(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka.example:9092")
        );

        try (TopologyTestDriver driver = driverFor(config, 1)) {
            TestInputTopic<String, byte[]> input = driver.createInputTopic("raw-alarms", new StringSerializer(), new ByteArraySerializer());
            TestOutputTopic<String, byte[]> output = driver.createOutputTopic("ztr-alarms", new StringDeserializer(), new ByteArrayDeserializer());

            input.pipeInput("k1", jsonBytes(Map.of("alarm", Map.of("id", "A-1", "severity", "CRITICAL"))));
            input.pipeInput("k2", jsonBytes(Map.of("alarm", Map.of("id", "A-2", "severity", "CLEARED"))));

            Map<String, Object> first = readJson(output.readValue());
            assertEquals("A-1", first.get("event_id"));
            assertEquals("NEW", first.get("event_type"));
            assertEquals("prod", first.get("environment"));

            Map<String, Object> second = readJson(output.readValue());
            assertEquals("A-2", second.get("event_id"));
            assertEquals("CLEAR", second.get("event_type"));
        }
    }

    @Test
    void filterDropsAlarmsThatDoNotMatchAnyRule() throws Exception {
        AlarmsToZtrConfig config = new AlarmsToZtrConfig(
            "raw-alarms",
            "ztr-alarms",
            "nokia",
            1.0d,
            Map.of(
                "default", Map.of("event_id", "$input.alarm.id"),
                "configmap", Map.of("event_id", "$input.alarm.id", "event_type", "NEW", "category", "config")
            ),
            "default",
            new AlarmsToZtrFilter("default", List.of(
                new AlarmsToZtrFilterRule("alarm.severity", "in", null, List.of("CRITICAL", "MAJOR"), null),
                new AlarmsToZtrFilterRule("alarm.key", "regex", "^CONFIG", null, "configmap")
            )),
            Map.of(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka.example:9092")
        );

        try (TopologyTestDriver driver = driverFor(config, 2)) {
            TestInputTopic<String, byte[]> input = driver.createInputTopic("raw-alarms", new StringSerializer(), new ByteArraySerializer());
            TestOutputTopic<String, byte[]> output = driver.createOutputTopic("ztr-alarms", new StringDeserializer(), new ByteArrayDeserializer());

            input.pipeInput("k1", jsonBytes(Map.of("alarm", Map.of("id", "A-1", "severity", "CRITICAL"))));
            input.pipeInput("k2", jsonBytes(Map.of("alarm", Map.of("id", "A-2", "severity", "MINOR", "key", "UNRELATED"))));
            input.pipeInput("k3", jsonBytes(Map.of("alarm", Map.of("id", "A-3", "severity", "MINOR", "key", "CONFIG_CHANGE"))));

            Map<String, Object> first = readJson(output.readValue());
            assertEquals("A-1", first.get("event_id"));

            Map<String, Object> third = readJson(output.readValue());
            assertEquals("A-3", third.get("event_id"));
            assertEquals("config", third.get("category"));

            assertTrue(output.isEmpty());
        }
    }

    @Test
    void malformedJsonIsDroppedWithoutFailingTopology() throws Exception {
        AlarmsToZtrConfig config = new AlarmsToZtrConfig(
            "raw-alarms",
            "ztr-alarms",
            "nokia",
            1.0d,
            Map.of("default", Map.of("event_id", "$input.alarm.id")),
            "default",
            null,
            Map.of(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka.example:9092")
        );

        try (TopologyTestDriver driver = driverFor(config, 3)) {
            TestInputTopic<String, byte[]> input = driver.createInputTopic("raw-alarms", new StringSerializer(), new ByteArraySerializer());
            TestOutputTopic<String, byte[]> output = driver.createOutputTopic("ztr-alarms", new StringDeserializer(), new ByteArrayDeserializer());

            input.pipeInput("k1", "not json".getBytes(StandardCharsets.UTF_8));
            input.pipeInput("k2", jsonBytes(Map.of("alarm", Map.of("id", "A-ok"))));

            Map<String, Object> result = readJson(output.readValue());
            assertEquals("A-ok", result.get("event_id"));
            assertTrue(output.isEmpty());
        }
    }

    @Test
    void propertiesEmbedJobIdAndLeaseEpochInApplicationId() {
        AlarmsToZtrConfig config = new AlarmsToZtrConfig(
            "raw-alarms",
            "ztr-alarms",
            "nokia",
            null,
            Map.of("default", Map.of("event_id", "$input.alarm.id")),
            "default",
            null,
            Map.of(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka.example:9092")
        );
        JobDefinition definition = jobDefinition(config);
        AlarmsToZtrTopologyFactory factory = new AlarmsToZtrTopologyFactory();

        Properties properties = factory.properties(definition, 9);

        assertEquals("job-alarms-9", properties.getProperty(StreamsConfig.APPLICATION_ID_CONFIG));
        assertEquals("kafka.example:9092", properties.getProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals(Serdes.StringSerde.class, properties.get(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG));
        assertEquals(Serdes.ByteArraySerde.class, properties.get(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG));
    }

    private static TopologyTestDriver driverFor(AlarmsToZtrConfig config, long leaseEpoch) {
        JobDefinition definition = jobDefinition(config);
        AlarmsToZtrTopologyFactory factory = new AlarmsToZtrTopologyFactory();
        Topology topology = factory.build(definition);
        Properties properties = factory.properties(definition, leaseEpoch);
        assertNotNull(topology);
        return new TopologyTestDriver(topology, properties);
    }

    private static JobDefinition jobDefinition(AlarmsToZtrConfig config) {
        return new JobDefinition(
            "job-alarms",
            1,
            JobType.ALARMS_TO_ZTR,
            DesiredJobState.ACTIVE,
            1,
            "site-a",
            LeasePolicy.defaults(),
            1,
            null,
            null,
            config,
            Map.of(),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }

    private static byte[] jsonBytes(Object value) throws Exception {
        return MAPPER.writeValueAsBytes(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(byte[] bytes) throws Exception {
        return MAPPER.readValue(bytes, Map.class);
    }
}
