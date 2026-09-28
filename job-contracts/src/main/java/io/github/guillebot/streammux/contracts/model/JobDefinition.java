package io.github.guillebot.streammux.contracts.model;

import io.github.guillebot.streammux.contracts.config.AlarmsToZtrConfig;
import io.github.guillebot.streammux.contracts.config.JsonEnricherConfig;
import io.github.guillebot.streammux.contracts.config.RandomSamplerConfig;
import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record JobDefinition(
    String jobId,
    long jobVersion,
    JobType jobType,
    DesiredJobState desiredState,
    int priority,
    String siteAffinity,
    LeasePolicy leasePolicy,
    int parallelism,
    RouteAppConfig routeAppConfig,
    RandomSamplerConfig randomSamplerConfig,
    AlarmsToZtrConfig alarmsToZtrConfig,
    JsonEnricherConfig jsonEnricherConfig,
    Map<String, String> labels,
    List<String> tags,
    Instant updatedAt,
    String updatedBy
) {
    public JobDefinition(
        String jobId,
        long jobVersion,
        JobType jobType,
        DesiredJobState desiredState,
        int priority,
        String siteAffinity,
        LeasePolicy leasePolicy,
        int parallelism,
        RouteAppConfig routeAppConfig,
        RandomSamplerConfig randomSamplerConfig,
        AlarmsToZtrConfig alarmsToZtrConfig,
        Map<String, String> labels,
        List<String> tags,
        Instant updatedAt,
        String updatedBy
    ) {
        this(
            jobId,
            jobVersion,
            jobType,
            desiredState,
            priority,
            siteAffinity,
            leasePolicy,
            parallelism,
            routeAppConfig,
            randomSamplerConfig,
            alarmsToZtrConfig,
            null,
            labels,
            tags,
            updatedAt,
            updatedBy
        );
    }
}
