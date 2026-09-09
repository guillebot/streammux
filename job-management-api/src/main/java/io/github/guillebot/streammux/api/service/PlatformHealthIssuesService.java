package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.api.config.HealthAlertProperties;
import io.github.guillebot.streammux.api.service.PlatformHealthService.KafkaHealth;
import io.github.guillebot.streammux.api.service.PlatformHealthService.PlatformHealth;
import io.github.guillebot.streammux.api.service.PlatformHealthService.TopicPresence;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.LagMetrics;
import io.github.guillebot.streammux.contracts.model.RuntimeState;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class PlatformHealthIssuesService {
    private final PlatformHealthService platformHealthService;
    private final JobStateStore stateStore;
    private final HealthAlertProperties alertProperties;

    public PlatformHealthIssuesService(
        PlatformHealthService platformHealthService,
        JobStateStore stateStore,
        HealthAlertProperties alertProperties
    ) {
        this.platformHealthService = platformHealthService;
        this.stateStore = stateStore;
        this.alertProperties = alertProperties;
    }

    public HealthIssuesSnapshot issues() {
        PlatformHealth platform = platformHealthService.snapshot();
        List<HealthIssue> issues = new ArrayList<>();
        collectKafkaIssues(platform, issues);
        collectJobIssues(issues);
        issues.sort(Comparator.comparingInt((HealthIssue i) -> severityRank(i.severity())).thenComparing(HealthIssue::title));
        return new HealthIssuesSnapshot(Instant.now(), List.copyOf(issues));
    }

    public HealthSummary summary() {
        return summaryFromSnapshot(issues());
    }

    /** Shared by summary and issues endpoints so HealthBell can call one API when needed. */
    public HealthSummary summaryFromSnapshot(HealthIssuesSnapshot snapshot) {
        int critical = 0;
        int warning = 0;
        int info = 0;
        for (HealthIssue issue : snapshot.issues()) {
            switch (issue.severity()) {
                case CRITICAL -> critical++;
                case WARNING -> warning++;
                case INFO -> info++;
            }
        }
        return new HealthSummary(snapshot.checkedAt(), critical, warning, info, true);
    }

    public long lagWarnThreshold() {
        return alertProperties.lagWarnThreshold();
    }

    private void collectKafkaIssues(PlatformHealth platform, List<HealthIssue> issues) {
        KafkaHealth kafka = platform.kafka();
        if ("DOWN".equals(kafka.status())) {
            issues.add(new HealthIssue(
                "kafka-down",
                IssueSeverity.CRITICAL,
                "Kafka unreachable",
                kafka.detail() != null ? kafka.detail() : kafka.bootstrapServers()
            ));
            return;
        }
        if ("DEGRADED".equals(kafka.status())) {
            issues.add(new HealthIssue(
                "kafka-degraded",
                IssueSeverity.WARNING,
                "Kafka degraded",
                "Broker count: " + kafka.brokerCount()
            ));
        }
        for (TopicPresence topic : kafka.topics()) {
            if (!topic.ok()) {
                issues.add(new HealthIssue(
                    "topic-" + topic.key(),
                    topic.exists() ? IssueSeverity.WARNING : IssueSeverity.CRITICAL,
                    topic.exists() ? "Topic misconfigured: " + topic.name() : "Missing topic: " + topic.name(),
                    "Expected cleanup: " + topic.expected()
                        + (topic.cleanupPolicy() != null ? ", actual: " + topic.cleanupPolicy() : "")
                ));
            }
        }
    }

    private void collectJobIssues(List<HealthIssue> issues) {
        Instant staleCutoff = Instant.now().minusSeconds(alertProperties.heartbeatStaleSeconds());
        long lagThreshold = alertProperties.lagWarnThreshold();

        for (JobDefinition definition : stateStore.listJobs()) {
            if (definition.desiredState() == DesiredJobState.DELETED) {
                continue;
            }
            String jobId = definition.jobId();
            var statusOpt = stateStore.getStatus(jobId);
            var leaseOpt = stateStore.getLease(jobId);

            if (definition.desiredState() == DesiredJobState.ACTIVE && leaseOpt.isEmpty()) {
                issues.add(new HealthIssue(
                    "lease-missing-" + jobId,
                    IssueSeverity.INFO,
                    "No lease for active job " + jobId,
                    "Orchestrator may not have claimed this job yet"
                ));
            }

            if (statusOpt.isEmpty()) {
                if (definition.desiredState() == DesiredJobState.ACTIVE) {
                    issues.add(new HealthIssue(
                        "status-missing-" + jobId,
                        IssueSeverity.WARNING,
                        "No runtime status for active job " + jobId,
                        null
                    ));
                }
                continue;
            }

            JobRuntimeStatus status = statusOpt.get();
            if (status.health() == HealthState.UNHEALTHY || status.state() == RuntimeState.FAILED) {
                issues.add(new HealthIssue(
                    "job-unhealthy-" + jobId,
                    IssueSeverity.CRITICAL,
                    "Job " + jobId + " is unhealthy",
                    status.failureReason()
                ));
            } else if (status.health() == HealthState.DEGRADED || status.state() == RuntimeState.DEGRADED) {
                issues.add(new HealthIssue(
                    "job-degraded-" + jobId,
                    IssueSeverity.WARNING,
                    "Job " + jobId + " is degraded",
                    status.failureReason()
                ));
            }

            if (status.lastHeartbeatAt() != null && status.lastHeartbeatAt().isBefore(staleCutoff)) {
                issues.add(new HealthIssue(
                    "heartbeat-stale-" + jobId,
                    IssueSeverity.WARNING,
                    "Stale heartbeat for job " + jobId,
                    "Last heartbeat " + Duration.between(status.lastHeartbeatAt(), Instant.now()).toMinutes() + " min ago"
                ));
            }

            LagMetrics lag = status.lagMetrics();
            if (lag != null && lag.inputLag() >= lagThreshold) {
                issues.add(new HealthIssue(
                    "lag-high-" + jobId,
                    IssueSeverity.WARNING,
                    "High input lag on job " + jobId,
                    "inputLag=" + lag.inputLag() + " (threshold " + lagThreshold + ")"
                ));
            }

            if (lag != null && lag.inputRatePerSecond() > 0 && lag.outputRatePerSecond() == 0 && lag.inputCount() > 1000) {
                issues.add(new HealthIssue(
                    "output-stalled-" + jobId,
                    IssueSeverity.WARNING,
                    "Output stalled on job " + jobId,
                    "Input active but output rate is zero"
                ));
            }
        }
    }

    public enum IssueSeverity {
        CRITICAL, WARNING, INFO
    }

    public record HealthIssue(String id, IssueSeverity severity, String title, String detail) {}

    public record HealthIssuesSnapshot(Instant checkedAt, List<HealthIssue> issues) {}

    public record HealthSummary(Instant checkedAt, int critical, int warning, int info, boolean prometheusReachable) {}

    private static int severityRank(IssueSeverity severity) {
        return switch (severity) {
            case CRITICAL -> 0;
            case WARNING -> 1;
            case INFO -> 2;
        };
    }
}
