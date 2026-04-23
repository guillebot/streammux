package io.github.guillebot.streammux.contracts.validation;

import io.github.guillebot.streammux.contracts.config.AlarmsToZtrConfig;
import io.github.guillebot.streammux.contracts.config.AlarmsToZtrFilter;
import io.github.guillebot.streammux.contracts.config.AlarmsToZtrFilterRule;
import io.github.guillebot.streammux.contracts.config.RandomSamplerConfig;
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

    @Test
    void allowsRandomSamplerWithinTopicPolicy() {
        TopicValidationPolicy policy = new TopicValidationPolicy(
            List.of("net.optimum.monitoring.netscout.fixed.voicesip.json"),
            List.of(),
            List.of(),
            List.of("lab.optimum.experimental.streamlens.streammux.")
        );

        assertDoesNotThrow(() -> JobDefinitionValidator.validate(randomSamplerJob(
            "net.optimum.monitoring.netscout.fixed.voicesip.json",
            "lab.optimum.experimental.streamlens.streammux.sampled",
            0.5d
        ), policy));
    }

    @Test
    void rejectsRandomSamplerRateAboveOne() {
        TopicValidationPolicy policy = TopicValidationPolicy.unrestricted();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> JobDefinitionValidator.validate(randomSamplerJob(
            "in-topic",
            "out-topic",
            1.01d
        ), policy));

        assertTrue(exception.getMessage().contains("randomSamplerConfig.rate"));
    }

    @Test
    void allowsAlarmsToZtrWithinTopicPolicy() {
        TopicValidationPolicy policy = new TopicValidationPolicy(
            List.of("net.optimum.monitoring.nokia.raw-alarms"),
            List.of(),
            List.of(),
            List.of("lab.optimum.experimental.streamlens.streammux.")
        );

        assertDoesNotThrow(() -> JobDefinitionValidator.validate(alarmsToZtrJob(
            "net.optimum.monitoring.nokia.raw-alarms",
            "lab.optimum.experimental.streamlens.streammux.nokia-ztr",
            "default",
            null
        ), policy));
    }

    @Test
    void rejectsAlarmsToZtrWithUnknownDefaultMapping() {
        TopicValidationPolicy policy = TopicValidationPolicy.unrestricted();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> JobDefinitionValidator.validate(alarmsToZtrJob(
            "in-topic",
            "out-topic",
            "missing",
            null
        ), policy));

        assertTrue(exception.getMessage().contains("defaultMappingName is not defined"));
    }

    @Test
    void rejectsAlarmsToZtrFilterRuleWithUnknownMapping() {
        TopicValidationPolicy policy = TopicValidationPolicy.unrestricted();
        AlarmsToZtrFilter filter = new AlarmsToZtrFilter(
            "default",
            List.of(new AlarmsToZtrFilterRule("alarm.severity", "eq", "CRITICAL", null, "does-not-exist"))
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> JobDefinitionValidator.validate(alarmsToZtrJob(
            "in-topic",
            "out-topic",
            "default",
            filter
        ), policy));

        assertTrue(exception.getMessage().contains("mappingName is not defined"));
    }

    @Test
    void rejectsAlarmsToZtrFilterInRuleMissingValues() {
        TopicValidationPolicy policy = TopicValidationPolicy.unrestricted();
        AlarmsToZtrFilter filter = new AlarmsToZtrFilter(
            "default",
            List.of(new AlarmsToZtrFilterRule("alarm.severity", "in", null, null, null))
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> JobDefinitionValidator.validate(alarmsToZtrJob(
            "in-topic",
            "out-topic",
            "default",
            filter
        ), policy));

        assertTrue(exception.getMessage().contains(".values must be a non-empty list"));
    }

    @Test
    void rejectsAlarmsToZtrSampleRateAboveOne() {
        TopicValidationPolicy policy = TopicValidationPolicy.unrestricted();
        AlarmsToZtrConfig config = new AlarmsToZtrConfig(
            "in-topic",
            "out-topic",
            "nokia",
            1.5d,
            Map.of("default", Map.of("event_id", "$input.alarm.id")),
            "default",
            null,
            Map.of()
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> JobDefinitionValidator.validate(jobOfType(
            JobType.ALARMS_TO_ZTR,
            null,
            null,
            config
        ), policy));

        assertTrue(exception.getMessage().contains("sampleRate must be between 0 and 1"));
    }

    private static JobDefinition alarmsToZtrJob(String inputTopic, String outputTopic, String defaultMappingName, AlarmsToZtrFilter filter) {
        AlarmsToZtrConfig config = new AlarmsToZtrConfig(
            inputTopic,
            outputTopic,
            "nokia",
            1.0d,
            Map.of("default", Map.of("event_id", "$input.alarm.id")),
            defaultMappingName,
            filter,
            Map.of()
        );
        return jobOfType(JobType.ALARMS_TO_ZTR, null, null, config);
    }

    private static JobDefinition randomSamplerJob(String inputTopic, String outputTopic, double rate) {
        return jobOfType(
            JobType.RANDOM_SAMPLER,
            null,
            new RandomSamplerConfig(inputTopic, outputTopic, rate, Map.of()),
            null
        );
    }

    private static JobDefinition jobDefinition(String inputTopic, String outputTopic) {
        RouteAppConfig routeAppConfig = new RouteAppConfig(
            inputTopic,
            PayloadFormat.JSON,
            PayloadFormat.JSON,
            null,
            List.of(new RouteDefinition("route-1", "Message", outputTopic)),
            Map.of(),
            Map.of()
        );
        return jobOfType(JobType.ROUTE_APP, routeAppConfig, null, null);
    }

    private static JobDefinition jobOfType(JobType type, RouteAppConfig routeAppConfig, RandomSamplerConfig randomSamplerConfig, AlarmsToZtrConfig alarmsToZtrConfig) {
        return new JobDefinition(
            "job-1",
            0,
            type,
            DesiredJobState.ACTIVE,
            1,
            "site-a",
            LeasePolicy.defaults(),
            1,
            routeAppConfig,
            randomSamplerConfig,
            alarmsToZtrConfig,
            Map.of(),
            List.of("test"),
            null,
            "test"
        );
    }
}
