package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.api.config.HealthAlertProperties;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LagMetrics;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.PayloadFormat;
import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;
import io.github.guillebot.streammux.contracts.model.RuntimeState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class LagAlertMonitorTest {
    @Test
    void publishesLagAlertAndRecovery() {
        JobStateStore store = new JobStateStore();
        JobCommandPublisher publisher = mock(JobCommandPublisher.class);
        HealthAlertProperties props = new HealthAlertProperties(100L, 300L);
        LagAlertMonitor monitor = new LagAlertMonitor(store, publisher, props);

        JobDefinition definition = sampleJob("job-1");
        store.upsertDefinition(definition);
        store.upsertStatus(status("job-1", 150L));

        monitor.checkLag();
        verify(publisher, times(1)).publishEvent(any());

        store.upsertStatus(status("job-1", 10L));
        monitor.checkLag();

        ArgumentCaptor<io.github.guillebot.streammux.contracts.event.JobEvent> captor =
            ArgumentCaptor.forClass(io.github.guillebot.streammux.contracts.event.JobEvent.class);
        verify(publisher, times(2)).publishEvent(captor.capture());
        assertEquals(EventType.LAG_ALERT, captor.getAllValues().get(0).eventType());
        assertEquals(EventType.LAG_RECOVERED, captor.getAllValues().get(1).eventType());
    }

    private static JobDefinition sampleJob(String jobId) {
        return new JobDefinition(
            jobId,
            1,
            JobType.ROUTE_APP,
            DesiredJobState.ACTIVE,
            1,
            "site-a",
            LeasePolicy.defaults(),
            1,
            new RouteAppConfig(
                "input-topic",
                PayloadFormat.JSON,
                PayloadFormat.JSON,
                null,
                List.of(new RouteDefinition("route-1", "true", "output-topic")),
                Map.of(),
                Map.of()
            ),
            null,
            null,
            Map.of(),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }

    private static JobRuntimeStatus status(String jobId, long inputLag) {
        return new JobRuntimeStatus(
            jobId,
            1,
            RuntimeState.RUNNING,
            HealthState.HEALTHY,
            Instant.now(),
            null,
            null,
            new LagMetrics(inputLag, 0, 0, 0, 0)
        );
    }
}
