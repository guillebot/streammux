package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.api.config.HealthAlertProperties;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.LagMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes {@link EventType#LAG_ALERT} / {@link EventType#LAG_RECOVERED} job events when
 * consumer lag crosses {@link HealthAlertProperties#lagWarnThreshold()}.
 */
@Component
public class LagAlertMonitor {
    private static final Logger LOGGER = LoggerFactory.getLogger(LagAlertMonitor.class);

    private final JobStateStore stateStore;
    private final JobCommandPublisher commandPublisher;
    private final HealthAlertProperties alertProperties;
    private final Map<String, Boolean> lagAlertActive = new ConcurrentHashMap<>();

    public LagAlertMonitor(
        JobStateStore stateStore,
        JobCommandPublisher commandPublisher,
        HealthAlertProperties alertProperties
    ) {
        this.stateStore = stateStore;
        this.commandPublisher = commandPublisher;
        this.alertProperties = alertProperties;
    }

    @Scheduled(fixedDelayString = "${streammux.health.lag-check-interval-ms:30000}")
    public void checkLag() {
        long threshold = alertProperties.lagWarnThreshold();
        for (JobDefinition definition : stateStore.listJobs()) {
            if (definition.desiredState() == DesiredJobState.DELETED) {
                lagAlertActive.remove(definition.jobId());
                continue;
            }
            JobRuntimeStatus status = stateStore.getStatus(definition.jobId()).orElse(null);
            if (status == null || status.lagMetrics() == null) {
                continue;
            }
            LagMetrics lag = status.lagMetrics();
            long inputLag = lag.inputLag();
            boolean high = inputLag >= threshold;
            String jobId = definition.jobId();
            Boolean wasHigh = lagAlertActive.put(jobId, high);
            if (high && !Boolean.TRUE.equals(wasHigh)) {
                publish(jobId, definition.jobVersion(), EventType.LAG_ALERT,
                    "Input lag " + inputLag + " exceeds threshold " + threshold,
                    Map.of("inputLag", inputLag, "threshold", threshold));
                LOGGER.warn("Job {} input lag {} exceeds threshold {}", jobId, inputLag, threshold);
            } else if (!high && Boolean.TRUE.equals(wasHigh)) {
                publish(jobId, definition.jobVersion(), EventType.LAG_RECOVERED,
                    "Input lag recovered to " + inputLag,
                    Map.of("inputLag", inputLag, "threshold", threshold));
                LOGGER.info("Job {} input lag recovered to {}", jobId, inputLag);
            }
        }
    }

    private void publish(String jobId, long version, EventType type, String message, Map<String, Object> attributes) {
        JobEvent event = new JobEvent(
            UUID.randomUUID().toString(),
            jobId,
            version,
            type,
            Instant.now(),
            null,
            "job-management-api",
            message,
            attributes,
            "lag-monitor"
        );
        stateStore.appendEvent(event);
        commandPublisher.publishEvent(event);
    }
}
