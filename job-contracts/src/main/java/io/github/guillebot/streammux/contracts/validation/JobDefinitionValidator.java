package io.github.guillebot.streammux.contracts.validation;

import io.github.guillebot.streammux.contracts.config.AlarmsToZtrConfig;
import io.github.guillebot.streammux.contracts.config.AlarmsToZtrFilter;
import io.github.guillebot.streammux.contracts.config.AlarmsToZtrFilterRule;
import io.github.guillebot.streammux.contracts.config.RandomSamplerConfig;
import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;

import java.util.Map;
import java.util.Set;

public final class JobDefinitionValidator {
    private JobDefinitionValidator() {}

    public static void validate(JobDefinition jobDefinition) {
        validate(jobDefinition, TopicValidationPolicy.unrestricted());
    }

    public static void validate(JobDefinition jobDefinition, TopicValidationPolicy topicValidationPolicy) {
        if (jobDefinition == null) throw new IllegalArgumentException("jobDefinition is required");
        if (isBlank(jobDefinition.jobId())) throw new IllegalArgumentException("jobId is required");
        if (jobDefinition.jobType() == null) throw new IllegalArgumentException("jobType is required");
        if (jobDefinition.desiredState() == null) throw new IllegalArgumentException("desiredState is required");
        if (jobDefinition.leasePolicy() == null) throw new IllegalArgumentException("leasePolicy is required");
        if (jobDefinition.jobType() == JobType.ROUTE_APP) {
            validateRouteAppConfig(jobDefinition.routeAppConfig(), topicValidationPolicy);
        }
        if (jobDefinition.jobType() == JobType.RANDOM_SAMPLER) {
            validateRandomSamplerConfig(jobDefinition.randomSamplerConfig(), topicValidationPolicy);
        }
        if (jobDefinition.jobType() == JobType.ALARMS_TO_ZTR) {
            validateAlarmsToZtrConfig(jobDefinition.alarmsToZtrConfig(), topicValidationPolicy);
        }
        if (jobDefinition.desiredState() == DesiredJobState.DELETED && jobDefinition.jobVersion() <= 0) throw new IllegalArgumentException("deleted jobs must include a valid version");
    }

    private static void validateRouteAppConfig(RouteAppConfig routeAppConfig, TopicValidationPolicy topicValidationPolicy) {
        if (routeAppConfig == null) {
            throw new IllegalArgumentException("routeAppConfig is required for route-app jobs");
        }
        if (isBlank(routeAppConfig.inputTopic())) {
            throw new IllegalArgumentException("routeAppConfig.inputTopic is required");
        }
        if (!topicValidationPolicy.isInputTopicAllowed(routeAppConfig.inputTopic())) {
            throw new IllegalArgumentException("routeAppConfig.inputTopic is not allowed: " + routeAppConfig.inputTopic());
        }
        if (routeAppConfig.routes() == null || routeAppConfig.routes().isEmpty()) {
            throw new IllegalArgumentException("routeAppConfig.routes must include at least one route");
        }

        for (RouteDefinition route : routeAppConfig.routes()) {
            if (route == null) {
                throw new IllegalArgumentException("routeAppConfig.routes cannot contain null routes");
            }
            if (isBlank(route.outputTopic())) {
                throw new IllegalArgumentException("route outputTopic is required");
            }
            if (!topicValidationPolicy.isOutputTopicAllowed(route.outputTopic())) {
                throw new IllegalArgumentException("route outputTopic is not allowed: " + route.outputTopic());
            }
        }
    }

    private static void validateRandomSamplerConfig(RandomSamplerConfig config, TopicValidationPolicy topicValidationPolicy) {
        if (config == null) {
            throw new IllegalArgumentException("randomSamplerConfig is required for RANDOM_SAMPLER jobs");
        }
        if (isBlank(config.inputTopic())) {
            throw new IllegalArgumentException("randomSamplerConfig.inputTopic is required");
        }
        if (!topicValidationPolicy.isInputTopicAllowed(config.inputTopic())) {
            throw new IllegalArgumentException("randomSamplerConfig.inputTopic is not allowed: " + config.inputTopic());
        }
        if (isBlank(config.outputTopic())) {
            throw new IllegalArgumentException("randomSamplerConfig.outputTopic is required");
        }
        if (!topicValidationPolicy.isOutputTopicAllowed(config.outputTopic())) {
            throw new IllegalArgumentException("randomSamplerConfig.outputTopic is not allowed: " + config.outputTopic());
        }
        if (Double.isNaN(config.rate()) || config.rate() < 0.0d || config.rate() > 1.0d) {
            throw new IllegalArgumentException("randomSamplerConfig.rate must be between 0 and 1 inclusive");
        }
    }

    private static void validateAlarmsToZtrConfig(AlarmsToZtrConfig config, TopicValidationPolicy topicValidationPolicy) {
        if (config == null) {
            throw new IllegalArgumentException("alarmsToZtrConfig is required for ALARMS_TO_ZTR jobs");
        }
        if (isBlank(config.inputTopic())) {
            throw new IllegalArgumentException("alarmsToZtrConfig.inputTopic is required");
        }
        if (!topicValidationPolicy.isInputTopicAllowed(config.inputTopic())) {
            throw new IllegalArgumentException("alarmsToZtrConfig.inputTopic is not allowed: " + config.inputTopic());
        }
        if (isBlank(config.outputTopic())) {
            throw new IllegalArgumentException("alarmsToZtrConfig.outputTopic is required");
        }
        if (!topicValidationPolicy.isOutputTopicAllowed(config.outputTopic())) {
            throw new IllegalArgumentException("alarmsToZtrConfig.outputTopic is not allowed: " + config.outputTopic());
        }
        if (config.sampleRate() != null) {
            double rate = config.sampleRate();
            if (Double.isNaN(rate) || rate < 0.0d || rate > 1.0d) {
                throw new IllegalArgumentException("alarmsToZtrConfig.sampleRate must be between 0 and 1 inclusive");
            }
        }
        Map<String, Map<String, Object>> mappings = config.mappings();
        if (mappings == null || mappings.isEmpty()) {
            throw new IllegalArgumentException("alarmsToZtrConfig.mappings must include at least one mapping");
        }
        for (Map.Entry<String, Map<String, Object>> entry : mappings.entrySet()) {
            if (isBlank(entry.getKey())) {
                throw new IllegalArgumentException("alarmsToZtrConfig.mappings keys must be non-blank");
            }
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                throw new IllegalArgumentException("alarmsToZtrConfig.mappings[" + entry.getKey() + "] must not be empty");
            }
        }
        if (isBlank(config.defaultMappingName())) {
            throw new IllegalArgumentException("alarmsToZtrConfig.defaultMappingName is required");
        }
        if (!mappings.containsKey(config.defaultMappingName())) {
            throw new IllegalArgumentException("alarmsToZtrConfig.defaultMappingName is not defined in mappings: " + config.defaultMappingName());
        }
        AlarmsToZtrFilter filter = config.filter();
        if (filter != null) {
            validateAlarmsToZtrFilter(filter, mappings.keySet());
        }
    }

    private static void validateAlarmsToZtrFilter(AlarmsToZtrFilter filter, Set<String> mappingNames) {
        if (isBlank(filter.defaultMappingName())) {
            throw new IllegalArgumentException("alarmsToZtrConfig.filter.defaultMappingName is required");
        }
        if (!mappingNames.contains(filter.defaultMappingName())) {
            throw new IllegalArgumentException("alarmsToZtrConfig.filter.defaultMappingName is not defined in mappings: " + filter.defaultMappingName());
        }
        if (filter.rules() == null || filter.rules().isEmpty()) {
            throw new IllegalArgumentException("alarmsToZtrConfig.filter.rules must include at least one rule");
        }
        Set<String> allowedOps = Set.of("eq", "ne", "not_eq", "in", "not_in", "regex", "exists");
        for (int i = 0; i < filter.rules().size(); i++) {
            AlarmsToZtrFilterRule rule = filter.rules().get(i);
            String prefix = "alarmsToZtrConfig.filter.rules[" + i + "]";
            if (rule == null) {
                throw new IllegalArgumentException(prefix + " must not be null");
            }
            if (isBlank(rule.path())) {
                throw new IllegalArgumentException(prefix + ".path is required");
            }
            if (isBlank(rule.op())) {
                throw new IllegalArgumentException(prefix + ".op is required");
            }
            String op = rule.op();
            if (!allowedOps.contains(op)) {
                throw new IllegalArgumentException(prefix + ".op is not supported: " + op);
            }
            switch (op) {
                case "in", "not_in" -> {
                    if (rule.values() == null || rule.values().isEmpty()) {
                        throw new IllegalArgumentException(prefix + ".values must be a non-empty list for op " + op);
                    }
                }
                case "eq", "ne", "not_eq", "regex" -> {
                    if (rule.value() == null) {
                        throw new IllegalArgumentException(prefix + ".value is required for op " + op);
                    }
                }
                default -> {
                }
            }
            if (rule.mappingName() != null && !rule.mappingName().isBlank() && !mappingNames.contains(rule.mappingName())) {
                throw new IllegalArgumentException(prefix + ".mappingName is not defined in mappings: " + rule.mappingName());
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
