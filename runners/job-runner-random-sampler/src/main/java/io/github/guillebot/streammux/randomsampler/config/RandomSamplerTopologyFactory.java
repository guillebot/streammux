package io.github.guillebot.streammux.randomsampler.config;

import io.github.guillebot.streammux.contracts.config.RandomSamplerConfig;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
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
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RandomSamplerTopologyFactory {
    public Topology build(JobDefinition definition) {
        RandomSamplerConfig config = definition.randomSamplerConfig();
        double rate = config.rate();
        StreamsBuilder builder = new StreamsBuilder();
        Serde<byte[]> valueSerde = Serdes.ByteArray();
        KStream<String, byte[]> source = builder.stream(config.inputTopic(), Consumed.with(Serdes.String(), valueSerde));
        source
            .filter((key, value) -> ThreadLocalRandom.current().nextDouble() < rate)
            .to(config.outputTopic(), Produced.with(Serdes.String(), valueSerde));
        return builder.build();
    }

    public Properties properties(JobDefinition definition, long leaseEpoch) {
        RandomSamplerConfig config = definition.randomSamplerConfig();
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
