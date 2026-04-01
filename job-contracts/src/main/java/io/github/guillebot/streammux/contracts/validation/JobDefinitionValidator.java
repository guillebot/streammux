package io.github.guillebot.streammux.contracts.validation;

import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobType;

public final class JobDefinitionValidator {
    private JobDefinitionValidator() {}

    public static void validate(JobDefinition jobDefinition) {
        if (jobDefinition == null) throw new IllegalArgumentException("jobDefinition is required");
        if (isBlank(jobDefinition.jobId())) throw new IllegalArgumentException("jobId is required");
        if (jobDefinition.jobType() == null) throw new IllegalArgumentException("jobType is required");
        if (jobDefinition.desiredState() == null) throw new IllegalArgumentException("desiredState is required");
        if (jobDefinition.leasePolicy() == null) throw new IllegalArgumentException("leasePolicy is required");
        if (jobDefinition.jobType() == JobType.ROUTE_APP && jobDefinition.routeAppConfig() == null) throw new IllegalArgumentException("routeAppConfig is required for route-app jobs");
        if (jobDefinition.desiredState() == DesiredJobState.DELETED && jobDefinition.jobVersion() <= 0) throw new IllegalArgumentException("deleted jobs must include a valid version");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
