package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LagMetrics;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.LeaseStatus;
import io.github.guillebot.streammux.contracts.model.RuntimeState;
import io.github.guillebot.streammux.contracts.model.WorkerMetadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobStateStoreTest {

    @Test
    void listJobsReturnsDefinitionsSortedByJobId() {
        JobStateStore store = new JobStateStore();
        store.upsertDefinition(jobDefinition("job-b"));
        store.upsertDefinition(jobDefinition("job-a"));

        List<String> jobIds = store.listJobs().stream().map(JobDefinition::jobId).toList();

        assertEquals(List.of("job-a", "job-b"), jobIds);
    }

    @Test
    void appendEventAccumulatesPerJob() {
        JobStateStore store = new JobStateStore();
        store.appendEvent(jobEvent("job-1", EventType.CREATED));
        store.appendEvent(jobEvent("job-1", EventType.UPDATED));

        assertEquals(List.of(EventType.CREATED, EventType.UPDATED), store.getEvents("job-1").stream().map(JobEvent::eventType).toList());
    }

    @Test
    void removeJobClearsDefinitionLeaseStatusAndEvents() {
        JobStateStore store = new JobStateStore();
        store.upsertDefinition(jobDefinition("job-1"));
        store.upsertLease(new JobLease("job-1", 1, "site-a", "instance-a", 2, LeaseStatus.RUNNING, Instant.parse("2024-01-01T00:01:00Z"), Instant.parse("2024-01-01T00:00:30Z")));
        store.upsertStatus(new JobRuntimeStatus(
            "job-1",
            1,
            RuntimeState.RUNNING,
            HealthState.HEALTHY,
            Instant.parse("2024-01-01T00:00:30Z"),
            new WorkerMetadata("worker-1", "route-app", "RUNNING", Map.of()),
            null,
            new LagMetrics(1, 2, 3)
        ));
        store.appendEvent(jobEvent("job-1", EventType.CREATED));

        store.removeJob("job-1");

        assertTrue(store.getJob("job-1").isEmpty());
        assertTrue(store.getLease("job-1").isEmpty());
        assertTrue(store.getStatus("job-1").isEmpty());
        assertTrue(store.getEvents("job-1").isEmpty());
    }

    @Test
    void getEventsReturnsEmptyListForUnknownJob() {
        JobStateStore store = new JobStateStore();

        assertFalse(store.getEvents("missing").iterator().hasNext());
    }

    private static JobDefinition jobDefinition(String jobId) {
        return new JobDefinition(
            jobId,
            1,
            JobType.ROUTE_APP,
            DesiredJobState.ACTIVE,
            1,
            "site-a",
            LeasePolicy.defaults(),
            1,
            null,
            null,
            null,
            Map.of(),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }

    private static JobEvent jobEvent(String jobId, EventType eventType) {
        return new JobEvent(
            "event-" + eventType,
            jobId,
            1,
            eventType,
            Instant.parse("2024-01-01T00:00:00Z"),
            null,
            "api",
            eventType.name(),
            Map.of()
        );
    }
}
