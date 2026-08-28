package io.github.guillebot.streammux.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.PayloadFormat;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobDefinitionSchemaProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JobDefinitionSchemaProvider provider = new JobDefinitionSchemaProvider(objectMapper);

    @Test
    void schemaDocumentIsSelfContainedAndSpecCompliant() {
        JsonNode schema = provider.getSchemaJson();

        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$schema").asText());
        assertEquals("#/$defs/JobDefinition", schema.get("$ref").asText());
        assertTrue(schema.has("$defs"), "root document should embed all definitions");
        assertNotNull(schema.at("/$defs/JobDefinition"), "root type must be present in $defs");
        assertNotNull(schema.at("/$defs/RouteAppConfig"), "referenced RouteAppConfig type must be inlined");
    }

    @Test
    void everyLocalRefTargetsDefs() {
        JsonNode schema = provider.getSchemaJson();
        assertAllRefsUnderDefs(schema);
    }

    @Test
    void closedObjectsForbidExtraProperties() {
        JsonNode routeAppConfig = provider.getSchemaJson().at("/$defs/RouteAppConfig");
        assertEquals(false, routeAppConfig.get("additionalProperties").asBoolean());
    }

    @Test
    void validateAcceptsWellFormedDefinition() throws Exception {
        JsonNode payload = objectMapper.valueToTree(sampleDefinition());
        assertDoesNotThrow(() -> provider.validate(payload));
    }

    @Test
    void validateRejectsWrongPrimitiveType() throws Exception {
        JsonNode payload = objectMapper.valueToTree(sampleDefinition());
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("jobId", 42);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> provider.validate(payload));
        assertTrue(ex.getMessage().toLowerCase().contains("jobid"), () -> "message should mention jobId, was: " + ex.getMessage());
    }

    @Test
    void validateRejectsUnknownField() throws Exception {
        JsonNode payload = objectMapper.valueToTree(sampleDefinition());
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("filterExpresion", "typo");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> provider.validate(payload));
        assertTrue(ex.getMessage().toLowerCase().contains("filterexpresion"), () -> "message should mention the typoed field, was: " + ex.getMessage());
    }

    @Test
    void validateRejectsBadEnum() throws Exception {
        JsonNode payload = objectMapper.valueToTree(sampleDefinition());
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("desiredState", "TOTALLY_INVALID");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> provider.validate(payload));
        assertTrue(
            ex.getMessage().toLowerCase().contains("desiredstate") || ex.getMessage().contains("TOTALLY_INVALID"),
            () -> "message should mention desiredState or the invalid enum value, was: " + ex.getMessage()
        );
    }

    private void assertAllRefsUnderDefs(JsonNode node) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual()) {
                String value = ref.asText();
                assertTrue(
                    value.startsWith("#/$defs/") || value.startsWith("https://"),
                    () -> "expected all local $ref to target #/$defs/*, saw: " + value
                );
            }
            node.fields().forEachRemaining(field -> assertAllRefsUnderDefs(field.getValue()));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                assertAllRefsUnderDefs(child);
            }
        }
    }

    private static JobDefinition sampleDefinition() {
        return new JobDefinition(
            "job-1",
            1,
            JobType.ROUTE_APP,
            DesiredJobState.ACTIVE,
            5,
            "site-a",
            LeasePolicy.defaults(),
            1,
            new RouteAppConfig(
                "input-topic",
                PayloadFormat.JSON,
                PayloadFormat.JSON,
                null,
                List.of(new RouteDefinition("route-1", "message.type == \"ALARM\"", "alerts")),
                Map.of(),
                Map.of()
            ),
            null,
            null,
            Map.of("team", "mux"),
            List.of("test"),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }
}
