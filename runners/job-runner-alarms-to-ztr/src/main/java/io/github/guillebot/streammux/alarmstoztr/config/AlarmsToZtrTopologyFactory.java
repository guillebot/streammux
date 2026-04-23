package io.github.guillebot.streammux.alarmstoztr.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import io.github.guillebot.streammux.alarmstoztr.decoder.AlarmDecoder;
import io.github.guillebot.streammux.alarmstoztr.decoder.FilteringDecoder;
import io.github.guillebot.streammux.alarmstoztr.decoder.MappingAlarmDecoder;
import io.github.guillebot.streammux.alarmstoztr.filter.FilterEngine;
import io.github.guillebot.streammux.contracts.config.AlarmsToZtrConfig;
import io.github.guillebot.streammux.contracts.config.AlarmsToZtrFilter;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class AlarmsToZtrTopologyFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmsToZtrTopologyFactory.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final MapType RAW_ALARM_TYPE = OBJECT_MAPPER.getTypeFactory().constructMapType(java.util.LinkedHashMap.class, String.class, Object.class);

    public Topology build(JobDefinition definition) {
        AlarmsToZtrConfig config = definition.alarmsToZtrConfig();
        AlarmDecoder decoder = buildDecoder(config);
        double sampleRate = config.sampleRate() != null ? config.sampleRate() : 1.0d;

        StreamsBuilder builder = new StreamsBuilder();
        KStream<String, byte[]> source = builder.stream(
            config.inputTopic(),
            Consumed.with(Serdes.String(), Serdes.ByteArray())
        );

        source
            .filter((key, value) -> value != null && value.length > 0)
            .filter((key, value) -> passesSampleRate(sampleRate))
            .mapValues(AlarmsToZtrTopologyFactory::parseJson)
            .filter((key, value) -> value != null)
            .mapValues(decoder::decode)
            .filter((key, value) -> value != null)
            .mapValues(AlarmsToZtrTopologyFactory::writeJson)
            .filter((key, value) -> value != null)
            .to(config.outputTopic(), Produced.with(Serdes.String(), Serdes.ByteArray()));

        return builder.build();
    }

    public Properties properties(JobDefinition definition, long leaseEpoch) {
        AlarmsToZtrConfig config = definition.alarmsToZtrConfig();
        Map<String, String> streamProperties = config.streamProperties() == null ? Map.of() : config.streamProperties();
        Properties properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, definition.jobId() + "-" + leaseEpoch);
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, streamProperties.getOrDefault(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"));
        properties.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        properties.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.ByteArraySerde.class);
        properties.putAll(streamProperties);
        return properties;
    }

    static AlarmDecoder buildDecoder(AlarmsToZtrConfig config) {
        AlarmsToZtrFilter filter = config.filter();
        if (filter == null) {
            Map<String, Object> template = config.mappings().get(config.defaultMappingName());
            return new MappingAlarmDecoder(template);
        }
        return new FilteringDecoder(new FilterEngine(filter), Map.copyOf(config.mappings()));
    }

    private static boolean passesSampleRate(double rate) {
        if (rate >= 1.0d) return true;
        if (rate <= 0.0d) return false;
        return ThreadLocalRandom.current().nextDouble() < rate;
    }

    private static Map<String, Object> parseJson(byte[] payload) {
        try {
            return OBJECT_MAPPER.readValue(payload, RAW_ALARM_TYPE);
        } catch (Exception ex) {
            LOGGER.warn("Dropping alarm: failed to parse raw JSON payload ({} bytes): {}", payload.length, ex.getMessage());
            return null;
        }
    }

    private static byte[] writeJson(Map<String, Object> outputAlarm) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(outputAlarm);
        } catch (Exception ex) {
            LOGGER.warn("Dropping output alarm: failed to serialize to JSON: {}", ex.getMessage());
            return null;
        }
    }
}
