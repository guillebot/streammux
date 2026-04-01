package io.github.guillebot.streammux.contracts.model;

import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record JobDefinition(String jobId, long jobVersion, JobType jobType, DesiredJobState desiredState, int priority, String siteAffinity, LeasePolicy leasePolicy, int parallelism, RouteAppConfig routeAppConfig, Map<String, String> labels, List<String> tags, Instant updatedAt, String updatedBy) {}
