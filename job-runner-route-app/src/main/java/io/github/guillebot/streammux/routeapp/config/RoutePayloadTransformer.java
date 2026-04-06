package io.github.guillebot.streammux.routeapp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.model.PayloadFormat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RoutePayloadTransformer {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final JsonFormat.Printer PROTOBUF_PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();
    private static final String PROTOBUF_DESCRIPTOR_BASE64 = "protobuf.descriptor.base64";
    private static final String PROTOBUF_MESSAGE_TYPE = "protobuf.message.type";
    private static final Pattern FIELD_MATCH_PATTERN = Pattern.compile("^\\s*(?<path>/[^\\s]+|[A-Za-z0-9_.$\\-\\[\\]]+)\\s*(?<operator>==|!=)\\s*(?<value>.+?)\\s*$");

    private final PayloadFormat inputFormat;
    private final PayloadFormat outputFormat;
    private final Descriptor protobufDescriptor;

    private RoutePayloadTransformer(PayloadFormat inputFormat, PayloadFormat outputFormat, Descriptor protobufDescriptor) {
        this.inputFormat = inputFormat;
        this.outputFormat = outputFormat;
        this.protobufDescriptor = protobufDescriptor;
    }

    static RoutePayloadTransformer from(RouteAppConfig config) {
        Descriptor descriptor = requiresProtobuf(config.inputFormat(), config.outputFormat()) ? resolveDescriptor(config) : null;
        return new RoutePayloadTransformer(config.inputFormat(), config.outputFormat(), descriptor);
    }

    boolean matches(byte[] payload, String filterExpression) {
        if (filterExpression == null || filterExpression.isBlank() || payload == null) {
            return false;
        }
        FieldMatchExpression fieldMatchExpression = tryParseFieldMatch(filterExpression);
        if (fieldMatchExpression != null) {
            return fieldMatchExpression.matches(normalizedPayloadNode(payload));
        }
        return normalizedPayload(payload).contains(filterExpression);
    }

    byte[] convert(byte[] payload) {
        if (payload == null) {
            return null;
        }
        if (inputFormat == outputFormat) {
            validate(payload, inputFormat);
            return payload;
        }
        return switch (inputFormat) {
            case JSON -> jsonToProtobuf(payload);
            case PROTOBUF -> protobufToJson(payload);
        };
    }

    private String normalizedPayload(byte[] payload) {
        return switch (inputFormat) {
            case JSON -> jsonAsString(payload);
            case PROTOBUF -> protobufAsJson(payload);
        };
    }

    private JsonNode normalizedPayloadNode(byte[] payload) {
        return switch (inputFormat) {
            case JSON -> jsonAsNode(payload);
            case PROTOBUF -> protobufAsNode(payload);
        };
    }

    private void validate(byte[] payload, PayloadFormat format) {
        switch (format) {
            case JSON -> jsonAsString(payload);
            case PROTOBUF -> protobufAsJson(payload);
        }
    }

    private String jsonAsString(byte[] payload) {
        try {
            JsonNode jsonNode = jsonAsNode(payload);
            return OBJECT_MAPPER.writeValueAsString(jsonNode);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decode JSON payload", ex);
        }
    }

    private JsonNode jsonAsNode(byte[] payload) {
        try {
            return OBJECT_MAPPER.readTree(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decode JSON payload", ex);
        }
    }

    private String protobufAsJson(byte[] payload) {
        try {
            return PROTOBUF_PRINTER.print(parseDynamicMessage(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decode Protobuf payload", ex);
        }
    }

    private JsonNode protobufAsNode(byte[] payload) {
        try {
            return OBJECT_MAPPER.readTree(protobufAsJson(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decode Protobuf payload", ex);
        }
    }

    private byte[] jsonToProtobuf(byte[] payload) {
        try {
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(protobufDescriptor);
            JsonFormat.parser().merge(new String(payload, StandardCharsets.UTF_8), builder);
            return builder.build().toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to convert JSON payload to Protobuf", ex);
        }
    }

    private byte[] protobufToJson(byte[] payload) {
        try {
            return protobufAsJson(payload).getBytes(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to convert Protobuf payload to JSON", ex);
        }
    }

    private DynamicMessage parseDynamicMessage(byte[] payload) throws InvalidProtocolBufferException {
        return DynamicMessage.parseFrom(protobufDescriptor, payload);
    }

    private static boolean requiresProtobuf(PayloadFormat inputFormat, PayloadFormat outputFormat) {
        return inputFormat == PayloadFormat.PROTOBUF || outputFormat == PayloadFormat.PROTOBUF;
    }

    private static FieldMatchExpression tryParseFieldMatch(String filterExpression) {
        Matcher matcher = FIELD_MATCH_PATTERN.matcher(filterExpression);
        if (!matcher.matches()) {
            return null;
        }

        String path = matcher.group("path");
        String operator = matcher.group("operator");
        JsonNode expectedValue = parseExpectedValue(matcher.group("value"));
        return new FieldMatchExpression(path, Operator.from(operator), expectedValue);
    }

    private static JsonNode parseExpectedValue(String rawValue) {
        String value = rawValue.trim();
        try {
            return OBJECT_MAPPER.readTree(value);
        } catch (Exception ignored) {
            return TextNode.valueOf(unquote(value));
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static Descriptor resolveDescriptor(RouteAppConfig config) {
        Map<String, String> serdeProperties = config.serdeProperties() == null ? Map.of() : config.serdeProperties();
        String descriptorBase64 = serdeProperties.get(PROTOBUF_DESCRIPTOR_BASE64);
        String messageType = serdeProperties.get(PROTOBUF_MESSAGE_TYPE);
        if (descriptorBase64 == null || descriptorBase64.isBlank()) {
            throw new IllegalStateException("Missing serde property " + PROTOBUF_DESCRIPTOR_BASE64 + " for Protobuf route-app job");
        }
        if (messageType == null || messageType.isBlank()) {
            throw new IllegalStateException("Missing serde property " + PROTOBUF_MESSAGE_TYPE + " for Protobuf route-app job");
        }
        try {
            FileDescriptorSet descriptorSet = FileDescriptorSet.parseFrom(Base64.getDecoder().decode(descriptorBase64));
            return resolveDescriptor(descriptorSet, messageType);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to resolve Protobuf descriptor for route-app job", ex);
        }
    }

    private static Descriptor resolveDescriptor(FileDescriptorSet descriptorSet, String messageType) throws DescriptorValidationException {
        Map<String, FileDescriptorProto> protoByName = new HashMap<>();
        for (FileDescriptorProto proto : descriptorSet.getFileList()) {
            protoByName.put(proto.getName(), proto);
        }

        Map<String, FileDescriptor> descriptorsByName = new HashMap<>();
        for (FileDescriptorProto proto : descriptorSet.getFileList()) {
            buildFileDescriptor(proto, protoByName, descriptorsByName);
        }

        String normalizedMessageType = messageType.startsWith(".") ? messageType.substring(1) : messageType;
        for (FileDescriptor fileDescriptor : descriptorsByName.values()) {
            Descriptor descriptor = findDescriptor(fileDescriptor, normalizedMessageType);
            if (descriptor != null) {
                return descriptor;
            }
        }
        throw new IllegalStateException("Message type not found in descriptor set: " + messageType);
    }

    private static FileDescriptor buildFileDescriptor(
        FileDescriptorProto proto,
        Map<String, FileDescriptorProto> protoByName,
        Map<String, FileDescriptor> descriptorsByName
    ) throws DescriptorValidationException {
        FileDescriptor existing = descriptorsByName.get(proto.getName());
        if (existing != null) {
            return existing;
        }

        FileDescriptor[] dependencies = new FileDescriptor[proto.getDependencyCount()];
        for (int index = 0; index < proto.getDependencyCount(); index++) {
            String dependencyName = proto.getDependency(index);
            FileDescriptorProto dependencyProto = protoByName.get(dependencyName);
            if (dependencyProto == null) {
                throw new IllegalStateException("Missing dependent descriptor: " + dependencyName);
            }
            dependencies[index] = buildFileDescriptor(dependencyProto, protoByName, descriptorsByName);
        }

        FileDescriptor descriptor = FileDescriptor.buildFrom(proto, dependencies);
        descriptorsByName.put(proto.getName(), descriptor);
        return descriptor;
    }

    private static Descriptor findDescriptor(FileDescriptor fileDescriptor, String messageType) {
        for (Descriptor descriptor : fileDescriptor.getMessageTypes()) {
            Descriptor resolved = findDescriptor(descriptor, messageType);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private static Descriptor findDescriptor(Descriptor descriptor, String messageType) {
        if (descriptor.getFullName().equals(messageType)) {
            return descriptor;
        }
        for (Descriptor nestedType : descriptor.getNestedTypes()) {
            Descriptor resolved = findDescriptor(nestedType, messageType);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private enum Operator {
        EQUALS,
        NOT_EQUALS;

        private static Operator from(String operator) {
            return switch (operator) {
                case "==" -> EQUALS;
                case "!=" -> NOT_EQUALS;
                default -> throw new IllegalArgumentException("Unsupported operator: " + operator);
            };
        }
    }

    private record FieldMatchExpression(String path, Operator operator, JsonNode expectedValue) {
        private boolean matches(JsonNode payload) {
            JsonNode actualValue = resolvePath(payload, path);
            if (actualValue.isMissingNode()) {
                return false;
            }

            boolean equals = actualValue.equals(expectedValue);
            return switch (operator) {
                case EQUALS -> equals;
                case NOT_EQUALS -> !equals;
            };
        }

        private JsonNode resolvePath(JsonNode payload, String path) {
            if (path.startsWith("/")) {
                return payload.at(path);
            }

            JsonNode current = payload;
            int index = 0;
            while (index < path.length()) {
                int segmentStart = index;
                while (index < path.length() && path.charAt(index) != '.' && path.charAt(index) != '[') {
                    index++;
                }

                if (segmentStart < index) {
                    current = current.path(path.substring(segmentStart, index));
                }

                while (index < path.length() && path.charAt(index) == '[') {
                    int endBracket = path.indexOf(']', index);
                    if (endBracket < 0) {
                        return current.path("__invalid_path__");
                    }
                    int arrayIndex = Integer.parseInt(path.substring(index + 1, endBracket));
                    current = current.path(arrayIndex);
                    index = endBracket + 1;
                }

                if (index < path.length() && path.charAt(index) == '.') {
                    index++;
                }
            }
            return current;
        }
    }
}
