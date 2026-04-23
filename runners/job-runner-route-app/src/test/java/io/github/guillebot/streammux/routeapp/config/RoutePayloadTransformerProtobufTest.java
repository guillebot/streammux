package io.github.guillebot.streammux.routeapp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.model.PayloadFormat;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePayloadTransformerProtobufTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final FileDescriptorProto FILE_DESCRIPTOR = FileDescriptorProto.newBuilder()
        .setName("test_message.proto")
        .setPackage("streammux.test")
        .addMessageType(DescriptorProto.newBuilder()
            .setName("TestMessage")
            .addField(field("message_name", 1, FieldDescriptorProto.Type.TYPE_STRING))
            .addField(field("severity", 2, FieldDescriptorProto.Type.TYPE_INT32))
            .addField(FieldDescriptorProto.newBuilder()
                .setName("tags")
                .setNumber(3)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)))
        .build();
    private static final String DESCRIPTOR_BASE64 = Base64.getEncoder()
        .encodeToString(FileDescriptorSet.newBuilder().addFile(FILE_DESCRIPTOR).build().toByteArray());
    private static final Descriptor DESCRIPTOR = descriptor();

    @Test
    void convertsJsonPayloadToProtobuf() throws Exception {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(config(PayloadFormat.JSON, PayloadFormat.PROTOBUF));

        byte[] protobufPayload = transformer.convert("""
            {"messageName":"Message-SMS","severity":7,"tags":["a","b"]}
            """.getBytes(StandardCharsets.UTF_8));

        DynamicMessage decoded = DynamicMessage.parseFrom(DESCRIPTOR, protobufPayload);

        assertEquals("Message-SMS", decoded.getField(DESCRIPTOR.findFieldByName("message_name")));
        assertEquals(7, decoded.getField(DESCRIPTOR.findFieldByName("severity")));
        assertEquals(List.of("a", "b"), decoded.getField(DESCRIPTOR.findFieldByName("tags")));
    }

    @Test
    void convertsProtobufPayloadToJson() throws Exception {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(config(PayloadFormat.PROTOBUF, PayloadFormat.JSON));
        DynamicMessage payload = DynamicMessage.newBuilder(DESCRIPTOR)
            .setField(DESCRIPTOR.findFieldByName("message_name"), "Message-SMS")
            .setField(DESCRIPTOR.findFieldByName("severity"), 9)
            .addRepeatedField(DESCRIPTOR.findFieldByName("tags"), "primary")
            .build();

        byte[] jsonPayload = transformer.convert(payload.toByteArray());

        assertEquals(
            Map.of("messageName", "Message-SMS", "severity", 9, "tags", List.of("primary")),
            OBJECT_MAPPER.readValue(jsonPayload, Map.class)
        );
    }

    @Test
    void matchesFieldExpressionsAgainstProtobufPayloads() throws Exception {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(config(PayloadFormat.PROTOBUF, PayloadFormat.PROTOBUF));
        DynamicMessage payload = DynamicMessage.newBuilder(DESCRIPTOR)
            .setField(DESCRIPTOR.findFieldByName("message_name"), "Message-SMS")
            .setField(DESCRIPTOR.findFieldByName("severity"), 5)
            .addRepeatedField(DESCRIPTOR.findFieldByName("tags"), "critical")
            .build();

        assertTrue(transformer.matches(payload.toByteArray(), "messageName == \"Message-SMS\""));
        assertTrue(transformer.matches(payload.toByteArray(), "tags[0] == \"critical\""));
    }

    @Test
    void returnsPayloadUnchangedWhenFormatsMatchForValidProtobuf() throws Exception {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(config(PayloadFormat.PROTOBUF, PayloadFormat.PROTOBUF));
        byte[] payload = DynamicMessage.newBuilder(DESCRIPTOR)
            .setField(DESCRIPTOR.findFieldByName("message_name"), "Message-SMS")
            .build()
            .toByteArray();

        assertArrayEquals(payload, transformer.convert(payload));
    }

    private static RouteAppConfig config(PayloadFormat inputFormat, PayloadFormat outputFormat) {
        return new RouteAppConfig(
            "input-topic",
            inputFormat,
            outputFormat,
            null,
            List.of(new RouteDefinition("route-1", "messageName == \"Message-SMS\"", "output-topic")),
            Map.of(),
            Map.of(
                "protobuf.descriptor.base64", DESCRIPTOR_BASE64,
                "protobuf.message.type", "streammux.test.TestMessage"
            )
        );
    }

    private static Descriptor descriptor() {
        try {
            FileDescriptor fileDescriptor = FileDescriptor.buildFrom(FILE_DESCRIPTOR, new FileDescriptor[0]);
            return fileDescriptor.findMessageTypeByName("TestMessage");
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build protobuf descriptor", ex);
        }
    }

    private static FieldDescriptorProto field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
            .setName(name)
            .setNumber(number)
            .setType(type)
            .build();
    }
}
