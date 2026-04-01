package io.github.guillebot.streammux.routeapp.config;

import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.PayloadFormat;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;
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
        StreamsBuilder builder = new StreamsBuilder();
        KStream<String, String> source = builder.stream(config.inputTopic(), Consumed.with(Serdes.String(), Serdes.String()));
        for (RouteDefinition route : config.routes()) {
            source.filter((key, value) -> matches(value, route.filterExpression())).to(route.outputTopic(), Produced.with(Serdes.String(), Serdes.String()));
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
        properties.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, valueSerdeClass(config.inputFormat()));
        properties.putAll(streamProperties);
        return properties;
    }

    private Class<?> valueSerdeClass(PayloadFormat payloadFormat) {
        return switch (payloadFormat) {
            case JSON, PROTOBUF -> Serdes.StringSerde.class;
        };
    }

    private boolean matches(String value, String filterExpression) {
        return filterExpression != null && !filterExpression.isBlank() && value != null && value.contains(filterExpression);
    }
}
