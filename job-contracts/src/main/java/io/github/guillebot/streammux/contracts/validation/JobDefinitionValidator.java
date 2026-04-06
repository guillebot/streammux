package io.github.guillebot.streammux.contracts.validation;

import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;

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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
