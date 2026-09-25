package io.github.guillebot.streammux.jsonenricher.config;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.guillebot.streammux.contracts.config.JsonEnricherConfig;
import io.github.guillebot.streammux.contracts.kafka.KafkaStreamsApplicationIds;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.validation.JoinKeyCel;
import io.github.guillebot.streammux.contracts.validation.JsonPayloadPath;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.GlobalKTable;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Properties;

@Component
public class JsonEnricherTopologyFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(JsonEnricherTopologyFactory.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public Topology build(JobDefinition definition) {
        JsonEnricherConfig config = definition.jsonEnricherConfig();
        JoinKeyCel.Compiled joinKeyCel = JoinKeyCel.compile(config.joinKeyCel());
        String enrichmentName = config.enrichmentName();
        String source = config.source();
        String joinKeyPath = config.joinKeyPath();

        StreamsBuilder builder = new StreamsBuilder();
        GlobalKTable<String, byte[]> lookup = builder.globalTable(
            config.lookupTopic(),
            Consumed.with(Serdes.String(), Serdes.ByteArray())
        );

        KStream<String, byte[]> sourceStream = builder.stream(
            config.inputTopic(),
            Consumed.with(Serdes.String(), Serdes.ByteArray())
        );

        sourceStream
            .filter((key, value) -> value != null && value.length > 0)
            .mapValues(payload -> toCandidate(payload, joinKeyPath, joinKeyCel))
            .filter((key, candidate) -> candidate != null)
            .leftJoin(
                lookup,
                (key, candidate) -> candidate.joinKey,
                JsonEnricherTopologyFactory::pair
            )
            .mapValues(pair -> writeEnvelope(source, enrichmentName, pair))
            .filter((key, value) -> value != null)
            .to(config.outputTopic(), Produced.with(Serdes.String(), Serdes.ByteArray()));

        return builder.build();
    }

    public Properties properties(JobDefinition definition, long leaseEpoch) {
        JsonEnricherConfig config = definition.jsonEnricherConfig();
        Map<String, String> streamProperties = config.streamProperties() == null ? Map.of() : config.streamProperties();
        Properties properties = new Properties();
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, streamProperties.getOrDefault(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"));
        properties.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        properties.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.ByteArraySerde.class);
        properties.putAll(streamProperties);
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, KafkaStreamsApplicationIds.applicationId(definition.jobId()));
        if (!properties.containsKey(StreamsConfig.NUM_STREAM_THREADS_CONFIG)) {
            properties.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, Math.max(1, definition.parallelism()));
        }
        return properties;
    }

    static JoinCandidate toCandidate(byte[] payload, String joinKeyPath, JoinKeyCel.Compiled joinKeyCel) {
        JsonNode content;
        try {
            content = OBJECT_MAPPER.readTree(payload);
        } catch (Exception ex) {
            LOGGER.debug("Dropping JSON_ENRICHER record: invalid JSON ({} bytes)", payload.length);
            return null;
        }
        if (content == null || content.isMissingNode() || content.isNull()) {
            return null;
        }
        JsonNode field = JsonPayloadPath.resolve(content, joinKeyPath);
        if (field == null || field.isMissingNode() || field.isNull()) {
            LOGGER.debug("Dropping JSON_ENRICHER record: join key path missing");
            return null;
        }
        String raw = field.isValueNode() ? field.asText() : field.toString();
        if (raw == null || raw.isBlank()) {
            LOGGER.debug("Dropping JSON_ENRICHER record: blank join key");
            return null;
        }
        String joinKey = joinKeyCel.apply(raw);
        if (joinKey == null || joinKey.isBlank()) {
            LOGGER.debug("Dropping JSON_ENRICHER record: CEL produced no join key");
            return null;
        }
        return new JoinCandidate(content, joinKey);
    }

    private static Joined pair(JoinCandidate candidate, byte[] lookupValue) {
        return new Joined(candidate.content, lookupValue);
    }

    static byte[] writeEnvelope(String source, String enrichmentName, Joined joined) {
        try {
            ObjectNode root = OBJECT_MAPPER.createObjectNode();
            root.put("source", source);
            ArrayNode content = root.putArray("content");
            content.add(joined.content);
            ArrayNode enrichment = root.putArray("enrichment");
            ObjectNode enrichmentEntry = enrichment.addObject();
            ArrayNode lookupArray = enrichmentEntry.putArray(enrichmentName);
            JsonNode lookupNode = parseLookup(joined.lookupValue);
            if (lookupNode != null) {
                lookupArray.add(lookupNode);
            }
            return OBJECT_MAPPER.writeValueAsBytes(root);
        } catch (Exception ex) {
            LOGGER.debug("Dropping JSON_ENRICHER record: failed to serialize envelope");
            return null;
        }
    }

    private static JsonNode parseLookup(byte[] lookupValue) {
        if (lookupValue == null || lookupValue.length == 0) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(lookupValue);
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            return node;
        } catch (Exception ex) {
            LOGGER.debug("JSON_ENRICHER lookup value was not JSON; treating as miss");
            return null;
        }
    }

    record JoinCandidate(JsonNode content, String joinKey) {}

    record Joined(JsonNode content, byte[] lookupValue) {}
}
