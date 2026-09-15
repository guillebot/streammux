package io.github.guillebot.streammux.contracts.serde;

import tools.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.Serdes;

public final class JsonSerdeFactory {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonSerdeFactory() {}

    public static <T> Serde<T> jsonSerde(Class<T> type) {
        Serializer<T> serializer = (topic, data) -> {
            try { return OBJECT_MAPPER.writeValueAsBytes(data); }
            catch (Exception ex) { throw new IllegalStateException("Failed to serialize " + type.getSimpleName(), ex); }
        };
        Deserializer<T> deserializer = (topic, data) -> {
            try { return data == null ? null : OBJECT_MAPPER.readValue(data, type); }
            catch (Exception ex) { throw new IllegalStateException("Failed to deserialize " + type.getSimpleName(), ex); }
        };
        return Serdes.serdeFrom(serializer, deserializer);
    }
}
