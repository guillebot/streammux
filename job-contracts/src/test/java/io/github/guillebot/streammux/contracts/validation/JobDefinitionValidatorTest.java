package io.github.guillebot.streammux.contracts.validation;

import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.PayloadFormat;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobDefinitionValidatorTest {
    @Test
    void allowsConfiguredInputAndOutputTopics() {
        TopicValidationPolicy policy = new TopicValidationPolicy(
            List.of("net.optimum.monitoring.netscout.fixed.voicesip.json"),
            List.of(),
            List.of(),
            List.of("lab.optimum.experimental.streamlens.streammux.")
        );

        assertDoesNotThrow(() -> JobDefinitionValidator.validate(jobDefinition(
            "net.optimum.monitoring.netscout.fixed.voicesip.json",
            "lab.optimum.experimental.streamlens.streammux.output1"
        ), policy));
    }

    @Test
    void rejectsInputTopicOutsideConfiguredPolicy() {
        TopicValidationPolicy policy = new TopicValidationPolicy(
            List.of("net.optimum.monitoring.allowed"),
            List.of("trusted.input."),
            List.of(),
            List.of("trusted.output.")
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> JobDefinitionValidator.validate(jobDefinition(
            "untrusted.input.topic",
            "trusted.output.topic"
        ), policy));

        assertTrue(exception.getMessage().contains("routeAppConfig.inputTopic is not allowed"));
    }

    @Test
    void rejectsOutputTopicOutsideConfiguredPolicy() {
        TopicValidationPolicy policy = new TopicValidationPolicy(
            List.of(),
            List.of("trusted.input."),
            List.of("trusted.output.topic"),
            List.of("trusted.output.")
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> JobDefinitionValidator.validate(jobDefinition(
            "trusted.input.topic",
            "untrusted.output.topic"
        ), policy));

        assertTrue(exception.getMessage().contains("route outputTopic is not allowed"));
    }

    private static JobDefinition jobDefinition(String inputTopic, String outputTopic) {
        return new JobDefinition(
            "job-1",
            0,
            JobType.ROUTE_APP,
            DesiredJobState.ACTIVE,
            1,
            "site-a",
            LeasePolicy.defaults(),
            1,
            new RouteAppConfig(
                inputTopic,
                PayloadFormat.JSON,
                PayloadFormat.JSON,
                null,
                List.of(new RouteDefinition("route-1", "Message", outputTopic)),
                Map.of(),
                Map.of()
            ),
            Map.of(),
            List.of("test"),
            null,
            "test"
        );
    }
}
