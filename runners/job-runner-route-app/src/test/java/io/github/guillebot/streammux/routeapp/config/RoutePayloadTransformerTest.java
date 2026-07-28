package io.github.guillebot.streammux.routeapp.config;

import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.model.PayloadFormat;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePayloadTransformerTest {

    @Test
    void matchesTopLevelFieldEquality() {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(testConfig());

        boolean matches = transformer.matches(
            """
            {"message_name":"Message-SMS","payload":"hello"}
            """.getBytes(StandardCharsets.UTF_8),
            "message_name == \"Message-SMS\""
        );

        assertTrue(matches);
    }

    @Test
    void matchesNestedFieldEquality() {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(testConfig());

        boolean matches = transformer.matches(
            """
            {"envelope":{"message_name":"Message-SMS"}}
            """.getBytes(StandardCharsets.UTF_8),
            "envelope.message_name == \"Message-SMS\""
        );

        assertTrue(matches);
    }

    @Test
    void matchesJsonPointerExpression() {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(testConfig());

        boolean matches = transformer.matches(
            """
            {"message_name":"Message-SMS","payload":"hello"}
            """.getBytes(StandardCharsets.UTF_8),
            "/message_name == \"Message-SMS\""
        );

        assertTrue(matches);
    }

    @Test
    void fallsBackToLegacySubstringMatching() {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(testConfig());

        boolean matches = transformer.matches(
            """
            {"message_name":"Message-SMS","payload":"hello"}
            """.getBytes(StandardCharsets.UTF_8),
            "\"message_name\":\"Message-SMS\""
        );

        assertTrue(matches);
    }

    @Test
    void returnsFalseWhenFieldDoesNotMatch() {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(testConfig());

        boolean matches = transformer.matches(
            """
            {"message_name":"Message-MMS","payload":"hello"}
            """.getBytes(StandardCharsets.UTF_8),
            "message_name == \"Message-SMS\""
        );

        assertFalse(matches);
    }

    @Test
    void supportsNotEqualsExpressions() {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(testConfig());

        boolean matches = transformer.matches(
            """
            {"message_name":"Message-MMS","payload":"hello"}
            """.getBytes(StandardCharsets.UTF_8),
            "message_name != \"Message-SMS\""
        );

        assertTrue(matches);
    }

    @Test
    void supportsArrayIndexPaths() {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(testConfig());

        boolean matches = transformer.matches(
            """
            {"routes":[{"enabled":true},{"enabled":false}]}
            """.getBytes(StandardCharsets.UTF_8),
            "routes[0].enabled == true"
        );

        assertTrue(matches);
    }

    @Test
    void returnsFalseForMissingPath() {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(testConfig());

        boolean matches = transformer.matches(
            """
            {"payload":"hello"}
            """.getBytes(StandardCharsets.UTF_8),
            "message_name == \"Message-SMS\""
        );

        assertFalse(matches);
    }

    @Test
    void returnsFalseForBlankExpressions() {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(testConfig());

        assertFalse(transformer.matches("{\"payload\":\"hello\"}".getBytes(StandardCharsets.UTF_8), "   "));
    }

    @Test
    void returnsPayloadUnchangedWhenFormatsMatch() {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(testConfig());
        byte[] payload = "{\"payload\":\"hello\"}".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(payload, transformer.convert(payload));
    }

    @Test
    void rejectsInvalidJsonPayloads() {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(testConfig());

        assertThrows(IllegalStateException.class, () -> transformer.convert("not-json".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void supportsCompoundAndExpressions() {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(testConfig());

        byte[] payload = """
            {"eventType":"NEW","subsystem":"OTHER","specificProblem":"Link down"}
            """.getBytes(StandardCharsets.UTF_8);

        assertTrue(transformer.matches(payload, "eventType == \"NEW\" && subsystem != \"FTTH-AGORA-SNMP\""));
        assertFalse(transformer.matches(payload, "eventType == \"CLEAR\" && subsystem != \"FTTH-AGORA-SNMP\""));
    }

    @Test
    void supportsInOperator() {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(testConfig());

        byte[] payload = """
            {"specificProblem":"Loss of signal for ONUi"}
            """.getBytes(StandardCharsets.UTF_8);

        assertTrue(transformer.matches(
            payload,
            "specificProblem in [\"Loss of signal for ONUi\", \"Receive dying-gasp of ONUi\"]"
        ));
        assertFalse(transformer.matches(payload, "specificProblem not in [\"Loss of signal for ONUi\"]"));
    }

    @Test
    void supportsNegatedCompoundExpressionForAgoraOnuNoise() {
        RoutePayloadTransformer transformer = RoutePayloadTransformer.from(testConfig());

        String filter = """
            eventType == "NEW" && !(subsystem == "FTTH-AGORA-SNMP" && specificProblem in ["Signal fail of ONUi", "Loss of signal for ONUi", "Receive dying-gasp of ONUi"])
            """.trim();

        byte[] keep = """
            {"eventType":"NEW","subsystem":"FTTH-AGORA-SNMP","specificProblem":"OLT unreachable"}
            """.getBytes(StandardCharsets.UTF_8);
        byte[] keepNonAgora = """
            {"eventType":"NEW","subsystem":"OTHER","specificProblem":"Loss of signal for ONUi"}
            """.getBytes(StandardCharsets.UTF_8);
        byte[] drop = """
            {"eventType":"NEW","subsystem":"FTTH-AGORA-SNMP","specificProblem":"Loss of signal for ONUi"}
            """.getBytes(StandardCharsets.UTF_8);
        byte[] dropClear = """
            {"eventType":"CLEAR","subsystem":"FTTH-AGORA-SNMP","specificProblem":"Loss of signal for ONUi"}
            """.getBytes(StandardCharsets.UTF_8);

        assertTrue(transformer.matches(keep, filter));
        assertTrue(transformer.matches(keepNonAgora, filter));
        assertFalse(transformer.matches(drop, filter));
        assertFalse(transformer.matches(dropClear, filter));
    }

    private RouteAppConfig testConfig() {
        return new RouteAppConfig(
            "input-topic",
            PayloadFormat.JSON,
            PayloadFormat.JSON,
            null,
            List.of(new RouteDefinition("route-1", "message_name == \"Message-SMS\"", "output-topic")),
            Map.of(),
            Map.of()
        );
    }
}
