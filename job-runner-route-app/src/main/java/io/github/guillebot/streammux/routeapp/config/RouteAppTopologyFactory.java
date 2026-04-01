package io.github.guillebot.streammux.routeapp.config;

import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Properties;

@Component
public class RouteAppTopologyFactory {
    public Topology build(JobDefinition definition) {
        RouteAppConfig config = definition.routeAppConfig();
        RoutePayloadTransformer payloadTransformer = RoutePayloadTransformer.from(config);
        StreamsBuilder builder = new StreamsBuilder();
        Serde<byte[]> valueSerde = Serdes.ByteArray();
        KStream<String, byte[]> source = builder.stream(config.inputTopic(), Consumed.with(Serdes.String(), valueSerde));
        for (RouteDefinition route : config.routes()) {
            source
                .filter((key, value) -> payloadTransformer.matches(value, route.filterExpression()))
                .mapValues(payloadTransformer::convert)
                .to(route.outputTopic(), Produced.with(Serdes.String(), valueSerde));
        }
        return builder.build();
    }

    public Properties properties(JobDefinition definition, long leaseEpoch) {
        RouteAppConfig config = definition.routeAppConfig();
        Map<String, String> streamProperties = config.streamProperties() == null ? Map.of() : config.streamProperties();
        Properties properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, definition.jobId() + "-" + leaseEpoch);
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, streamProperties.getOrDefault(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"));
        properties.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        properties.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.ByteArraySerde.class);
        properties.putAll(streamProperties);
        return properties;
    }
}
