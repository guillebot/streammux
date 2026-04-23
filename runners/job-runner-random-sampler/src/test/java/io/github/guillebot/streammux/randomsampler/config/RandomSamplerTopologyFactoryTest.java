package io.github.guillebot.streammux.randomsampler.config;

import io.github.guillebot.streammux.contracts.config.RandomSamplerConfig;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomSamplerTopologyFactoryTest {

    @Test
    void rateOneForwardsAllMessages() {
        JobDefinition definition = jobDefinition(1.0d);
        RandomSamplerTopologyFactory factory = new RandomSamplerTopologyFactory();
        Topology topology = factory.build(definition);
        Properties properties = factory.properties(definition, 2);

        try (TopologyTestDriver driver = new TopologyTestDriver(topology, properties)) {
            TestInputTopic<String, byte[]> inputTopic = driver.createInputTopic(
                "input-topic",
                new StringSerializer(),
                new ByteArraySerializer()
            );
            TestOutputTopic<String, byte[]> outputTopic = driver.createOutputTopic(
                "output-topic",
                new StringDeserializer(),
                new ByteArrayDeserializer()
            );

            byte[] payload = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
            inputTopic.pipeInput("k1", payload);
            assertArrayEquals(payload, outputTopic.readValue());
            assertTrue(outputTopic.isEmpty());
        }
    }

    @Test
    void rateOnePreservesKeysAcrossMultipleMessages() {
        JobDefinition definition = jobDefinition(1.0d);
        RandomSamplerTopologyFactory factory = new RandomSamplerTopologyFactory();
        Topology topology = factory.build(definition);
        Properties properties = factory.properties(definition, 3);

        try (TopologyTestDriver driver = new TopologyTestDriver(topology, properties)) {
            TestInputTopic<String, byte[]> inputTopic = driver.createInputTopic(
                "input-topic",
                new StringSerializer(),
                new ByteArraySerializer()
            );
            TestOutputTopic<String, byte[]> outputTopic = driver.createOutputTopic(
                "output-topic",
                new StringDeserializer(),
                new ByteArrayDeserializer()
            );

            inputTopic.pipeInput("k1", "one".getBytes(StandardCharsets.UTF_8));
            inputTopic.pipeInput("k2", "two".getBytes(StandardCharsets.UTF_8));

            assertEquals("k1", outputTopic.readKeyValue().key);
            assertEquals("k2", outputTopic.readKeyValue().key);
            assertTrue(outputTopic.isEmpty());
        }
    }

    @Test
    void rateZeroDropsAllMessages() {
        JobDefinition definition = jobDefinition(0.0d);
        RandomSamplerTopologyFactory factory = new RandomSamplerTopologyFactory();
        Topology topology = factory.build(definition);
        Properties properties = factory.properties(definition, 1);

        try (TopologyTestDriver driver = new TopologyTestDriver(topology, properties)) {
            TestInputTopic<String, byte[]> inputTopic = driver.createInputTopic(
                "input-topic",
                new StringSerializer(),
                new ByteArraySerializer()
            );
            TestOutputTopic<String, byte[]> outputTopic = driver.createOutputTopic(
                "output-topic",
                new StringDeserializer(),
                new ByteArrayDeserializer()
            );

            byte[] payload = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
            inputTopic.pipeInput("k1", payload);
            assertTrue(outputTopic.isEmpty());
        }
    }

    @Test
    void ratePointZeroOnePassesRoughlyOnePercentOfManyMessages() {
        JobDefinition definition = jobDefinition(0.01d);
        RandomSamplerTopologyFactory factory = new RandomSamplerTopologyFactory();
        Topology topology = factory.build(definition);
        Properties properties = factory.properties(definition, 1);

        try (TopologyTestDriver driver = new TopologyTestDriver(topology, properties)) {
            TestInputTopic<String, byte[]> inputTopic = driver.createInputTopic(
                "input-topic",
                new StringSerializer(),
                new ByteArraySerializer()
            );
            TestOutputTopic<String, byte[]> outputTopic = driver.createOutputTopic(
                "output-topic",
                new StringDeserializer(),
                new ByteArrayDeserializer()
            );

            byte[] payload = "x".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int n = 50_000;
            for (int i = 0; i < n; i++) {
                inputTopic.pipeInput("k" + i, payload);
            }
            long passed = outputTopic.readValuesToList().size();
            assertTrue(passed >= 350 && passed <= 650, "expected ~1% of " + n + " ≈ 500, got " + passed);
        }
    }

    @Test
    void buildsPropertiesWithLeaseScopedApplicationId() {
        JobDefinition definition = jobDefinition(0.5d);
        RandomSamplerTopologyFactory factory = new RandomSamplerTopologyFactory();
        Properties properties = factory.properties(definition, 7);
        assertEquals("job-1-7", properties.getProperty(StreamsConfig.APPLICATION_ID_CONFIG));
        assertEquals("kafka.example:9092", properties.getProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG));
    }

    @Test
    void propertiesUseDefaultBootstrapWhenStreamPropertiesNull() {
        JobDefinition definition = jobDefinitionWithStreamProps(0.5d, null);
        RandomSamplerTopologyFactory factory = new RandomSamplerTopologyFactory();
        Properties properties = factory.properties(definition, 1);
        assertEquals("localhost:9092", properties.getProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG));
    }

    @Test
    void propertiesProvideSerdeDefaultsWhenNotConfigured() {
        JobDefinition definition = jobDefinitionWithStreamProps(
            0.5d,
            Map.of(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka.example:9092")
        );
        RandomSamplerTopologyFactory factory = new RandomSamplerTopologyFactory();
        Properties properties = factory.properties(definition, 11);

        assertEquals(Serdes.StringSerde.class, properties.get(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG));
        assertEquals(Serdes.ByteArraySerde.class, properties.get(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG));
    }

    @Test
    void propertiesAllowStreamPropertiesToOverrideSerdeDefaults() {
        JobDefinition definition = jobDefinitionWithStreamProps(
            0.5d,
            Map.of(
                StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka.example:9092",
                StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, "custom.KeySerde",
                StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, "custom.ValueSerde"
            )
        );
        RandomSamplerTopologyFactory factory = new RandomSamplerTopologyFactory();
        Properties properties = factory.properties(definition, 12);

        assertEquals("custom.KeySerde", properties.getProperty(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG));
        assertEquals("custom.ValueSerde", properties.getProperty(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG));
    }

    private static JobDefinition jobDefinition(double rate) {
        return new JobDefinition(
            "job-1",
            1,
            JobType.RANDOM_SAMPLER,
            DesiredJobState.ACTIVE,
            1,
            "site-a",
            LeasePolicy.defaults(),
            1,
            null,
            new RandomSamplerConfig(
                "input-topic",
                "output-topic",
                rate,
                Map.of(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka.example:9092")
            ),
            null,
            Map.of(),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }

    private static JobDefinition jobDefinitionWithStreamProps(double rate, Map<String, String> streamProperties) {
        return new JobDefinition(
            "job-1",
            1,
            JobType.RANDOM_SAMPLER,
            DesiredJobState.ACTIVE,
            1,
            "site-a",
            LeasePolicy.defaults(),
            1,
            null,
            new RandomSamplerConfig(
                "input-topic",
                "output-topic",
                rate,
                streamProperties
            ),
            null,
            Map.of(),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }
}
