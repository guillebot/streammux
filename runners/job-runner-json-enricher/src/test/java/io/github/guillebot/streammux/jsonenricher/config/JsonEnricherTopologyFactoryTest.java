package io.github.guillebot.streammux.jsonenricher.config;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.guillebot.streammux.contracts.config.JsonEnricherConfig;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.validation.JoinKeyCel;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonEnricherTopologyFactoryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void joinsNormalizedAccountAndWrapsEnvelope() throws Exception {
        JobDefinition definition = jobDefinition();
        JsonEnricherTopologyFactory factory = new JsonEnricherTopologyFactory();
        Topology topology = factory.build(definition);
        Properties properties = factory.properties(definition, 1);

        try (TopologyTestDriver driver = new TopologyTestDriver(topology, properties)) {
            TestInputTopic<String, byte[]> lookupTopic = driver.createInputTopic(
                "lookup-topic",
                new StringSerializer(),
                new ByteArraySerializer()
            );
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

            lookupTopic.pipeInput("770793819901", "{\"cmtsNm\":\"lab-cmts\"}".getBytes(StandardCharsets.UTF_8));
            inputTopic.pipeInput("k1", "{\"AccountNum\":\"7707-938199-1\",\"JobNumber\":\"WO-1\"}".getBytes(StandardCharsets.UTF_8));

            JsonNode out = MAPPER.readTree(outputTopic.readValue());
            assertEquals("csg", out.get("source").asText());
            assertEquals("WO-1", out.get("content").get(0).get("JobNumber").asText());
            assertEquals("lab-cmts", out.get("enrichment").get(0).get("custdata").get(0).get("cmtsNm").asText());
            assertTrue(outputTopic.isEmpty());
        }
    }

    @Test
    void missYieldsEmptyCustdataArray() throws Exception {
        JobDefinition definition = jobDefinition();
        JsonEnricherTopologyFactory factory = new JsonEnricherTopologyFactory();
        Topology topology = factory.build(definition);
        Properties properties = factory.properties(definition, 1);

        try (TopologyTestDriver driver = new TopologyTestDriver(topology, properties)) {
            driver.createInputTopic("lookup-topic", new StringSerializer(), new ByteArraySerializer());
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

            inputTopic.pipeInput("k1", "{\"AccountNum\":\"7707-938199-1\"}".getBytes(StandardCharsets.UTF_8));
            JsonNode out = MAPPER.readTree(outputTopic.readValue());
            assertEquals(0, out.get("enrichment").get(0).get("custdata").size());
        }
    }

    @Test
    void dropsWhenJoinFieldMissing() {
        JobDefinition definition = jobDefinition();
        JsonEnricherTopologyFactory factory = new JsonEnricherTopologyFactory();
        Topology topology = factory.build(definition);
        Properties properties = factory.properties(definition, 1);

        try (TopologyTestDriver driver = new TopologyTestDriver(topology, properties)) {
            driver.createInputTopic("lookup-topic", new StringSerializer(), new ByteArraySerializer());
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

            inputTopic.pipeInput("k1", "{\"JobNumber\":\"WO-1\"}".getBytes(StandardCharsets.UTF_8));
            assertTrue(outputTopic.isEmpty());
        }
    }

    private static JobDefinition jobDefinition() {
        return new JobDefinition(
            "job-1",
            1,
            JobType.JSON_ENRICHER,
            DesiredJobState.ACTIVE,
            1,
            "site-a",
            LeasePolicy.defaults(),
            1,
            null,
            null,
            null,
            new JsonEnricherConfig(
                "input-topic",
                "output-topic",
                "csg",
                "AccountNum",
                JoinKeyCel.HYPHENATED_ACCOUNT_CEL,
                "lookup-topic",
                "custdata",
                Map.of(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka.example:9092")
            ),
            Map.of(),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }
}
