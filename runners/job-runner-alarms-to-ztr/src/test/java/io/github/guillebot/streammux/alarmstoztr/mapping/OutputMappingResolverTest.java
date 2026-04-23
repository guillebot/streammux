package io.github.guillebot.streammux.alarmstoztr.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OutputMappingResolverTest {

    @Test
    void resolve_replacesInputReferences_andKeepsStructure() {
        Map<String, Object> template = Map.of(
            "event_id", "$input.alarm.id",
            "summary", "Alarm $input.alarm.name on $input.extension.source",
            "context", Map.of(
                "severity", "$input.alarm.severity",
                "constant", "prod"
            ),
            "labels", List.of("$input.extension.source", "prod")
        );

        Map<String, Object> input = Map.of(
            "alarm", Map.of(
                "id", "A-123",
                "name", "CPU High",
                "severity", "CRITICAL"
            ),
            "extension", Map.of("source", "CPRO")
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> resolved = (Map<String, Object>) OutputMappingResolver.resolve(template, input);

        assertEquals("A-123", resolved.get("event_id"));
        assertEquals("Alarm CPU High on CPRO", resolved.get("summary"));

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) resolved.get("context");
        assertEquals("CRITICAL", context.get("severity"));
        assertEquals("prod", context.get("constant"));

        @SuppressWarnings("unchecked")
        List<Object> labels = (List<Object>) resolved.get("labels");
        assertEquals(List.of("CPRO", "prod"), labels);
    }

    @Test
    void resolve_valueMap_usesMatchedValue_orDefault() {
        Map<String, Object> template = Map.of(
            "event_type", Map.of(
                "$input", "alarm.severity",
                "$map", Map.of(
                    "CLEARED", "CLEAR",
                    "default", "NEW"
                )
            )
        );

        Map<String, Object> clearedInput = Map.of("alarm", Map.of("severity", "CLEARED"));
        @SuppressWarnings("unchecked")
        Map<String, Object> clearedResolved = (Map<String, Object>) OutputMappingResolver.resolve(template, clearedInput);
        assertEquals("CLEAR", clearedResolved.get("event_type"));

        Map<String, Object> majorInput = Map.of("alarm", Map.of("severity", "MAJOR"));
        @SuppressWarnings("unchecked")
        Map<String, Object> majorResolved = (Map<String, Object>) OutputMappingResolver.resolve(template, majorInput);
        assertEquals("NEW", majorResolved.get("event_type"));
    }

    @Test
    void resolve_missingPath_interpolatesPathName() {
        Map<String, Object> template = Map.of("missing", "$input.alarm.not_present");
        Map<String, Object> input = Map.of("alarm", Map.of("id", "A-1"));

        @SuppressWarnings("unchecked")
        Map<String, Object> resolved = (Map<String, Object>) OutputMappingResolver.resolve(template, input);

        assertEquals("alarm.not_present", resolved.get("missing"));
    }

    @Test
    void getByPath_returnsNestedValue_orNull() {
        Map<String, Object> input = Map.of(
            "alarm", Map.of(
                "severity", "CRITICAL",
                "metadata", "leaf"
            )
        );

        assertEquals("CRITICAL", OutputMappingResolver.getByPath(input, "alarm.severity"));
        assertNull(OutputMappingResolver.getByPath(input, "alarm.unknown"));
        assertNull(OutputMappingResolver.getByPath(input, "alarm.metadata.value"));
    }

    @Test
    void getByPath_supportsKvListKeySelector() {
        Map<String, Object> input = Map.of(
            "body", Map.of(
                "kvlistValue", Map.of(
                    "values", List.of(
                        Map.of("key", "status", "value", Map.of("stringValue", "firing")),
                        Map.of("key", "truncatedAlerts", "value", Map.of("doubleValue", 0))
                    )
                )
            )
        );

        assertEquals("firing",
            OutputMappingResolver.getByPath(input, "body.kvlistValue.values[key=status].value.stringValue"));
        assertEquals(0,
            OutputMappingResolver.getByPath(input, "body.kvlistValue.values[key=truncatedAlerts].value.doubleValue"));
        assertNull(OutputMappingResolver.getByPath(input, "body.kvlistValue.values[key=unknown].value.stringValue"));
    }

    @Test
    void getByPath_supportsNumericIndexInPath() {
        Map<String, Object> input = Map.of(
            "resourceLogs", List.of(
                Map.of(
                    "scopeLogs", List.of(
                        Map.of(
                            "logRecords", List.of(
                                Map.of(
                                    "body", Map.of(
                                        "kvlistValue", Map.of(
                                            "values", List.of(
                                                Map.of("key", "status", "value", Map.of("stringValue", "firing"))
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        );

        assertEquals("firing",
            OutputMappingResolver.getByPath(input, "resourceLogs.0.scopeLogs.0.logRecords.0.body.kvlistValue.values[key=status].value.stringValue"));
        assertNull(OutputMappingResolver.getByPath(input, "resourceLogs.1.scopeLogs.0.logRecords.0.body"));
    }
}
