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

    @Test
    void listRecentEventsReturnsNewestFirstWithFilters() {
        JobStateStore store = new JobStateStore();
        store.appendEvent(jobEvent("job-a", EventType.CREATED, Instant.parse("2024-01-01T00:00:00Z")));
        store.appendEvent(jobEvent("job-b", EventType.STARTED, Instant.parse("2024-01-01T00:00:01Z")));
        store.appendEvent(jobEvent("job-a", EventType.UPDATED, Instant.parse("2024-01-01T00:00:02Z")));

        assertEquals(2, store.listRecentEvents(10, "job-a", null, null).size());
        assertEquals(EventType.UPDATED, store.listRecentEvents(1, "job-a", null, null).getFirst().eventType());
        assertEquals(1, store.listRecentEvents(10, null, List.of(EventType.STARTED), null).size());
    }

    @Test
    void listRecentEventsMatchesAnyOfMultipleEventTypes() {
        JobStateStore store = new JobStateStore();
        store.appendEvent(jobEvent("job-a", EventType.CREATED, Instant.parse("2024-01-01T00:00:00Z")));
        store.appendEvent(jobEvent("job-a", EventType.PAUSED, Instant.parse("2024-01-01T00:00:01Z")));
        store.appendEvent(jobEvent("job-a", EventType.STARTED, Instant.parse("2024-01-01T00:00:02Z")));
        store.appendEvent(jobEvent("job-a", EventType.UPDATED, Instant.parse("2024-01-01T00:00:03Z")));

        assertEquals(2, store.listRecentEvents(10, null, List.of(EventType.PAUSED, EventType.STARTED), null).size());
        assertEquals(4, store.listRecentEvents(10, null, List.of(), null).size());
        assertEquals(4, store.listRecentEvents(10, null, null, null).size());
    }

    @Test
    void listRecentEventsMatchesJobIdSubstringCaseInsensitively() {
        JobStateStore store = new JobStateStore();
        store.appendEvent(jobEvent("route-app-alpha", EventType.CREATED, Instant.parse("2024-01-01T00:00:00Z"), "operator"));
        store.appendEvent(jobEvent("route-app-beta", EventType.STARTED, Instant.parse("2024-01-01T00:00:01Z"), "operator"));
        store.appendEvent(jobEvent("sampler-1", EventType.UPDATED, Instant.parse("2024-01-01T00:00:02Z"), "operator"));

        assertEquals(2, store.listRecentEvents(10, "ROUTE", null, null).size());
        assertEquals(2, store.listRecentEvents(10, "  route-app  ", null, null).size());
        assertEquals(1, store.listRecentEvents(10, "beta", null, null).size());
        assertEquals(0, store.listRecentEvents(10, "missing", null, null).size());
    }

    @Test
    void listRecentEventsMatchesActorSubstringCaseInsensitively() {
        JobStateStore store = new JobStateStore();
        store.appendEvent(jobEvent("job-a", EventType.CREATED, Instant.parse("2024-01-01T00:00:00Z"), "jsolarin@optimum.com"));
        store.appendEvent(jobEvent("job-a", EventType.UPDATED, Instant.parse("2024-01-01T00:00:01Z"), "gschimmel@optimum.com"));
        store.appendEvent(jobEvent("job-a", EventType.PAUSED, Instant.parse("2024-01-01T00:00:02Z"), null));

        assertEquals(1, store.listRecentEvents(10, null, null, "jsolarin").size());
        assertEquals(1, store.listRecentEvents(10, null, null, "JSOLARIN").size());
        assertEquals(2, store.listRecentEvents(10, null, null, "@optimum.com").size());
        assertEquals(0, store.listRecentEvents(10, null, null, "unknown").size());
    }

    private static JobEvent jobEvent(String jobId, EventType eventType, Instant eventTime) {
        return jobEvent(jobId, eventType, eventTime, "tester");
    }

    private static JobEvent jobEvent(String jobId, EventType eventType, Instant eventTime, String actor) {
        return new JobEvent(
            "event-" + jobId + "-" + eventType,
            jobId,
            1,
            eventType,
            eventTime,
            null,
            "api",
            eventType.name(),
            Map.of(),
            actor
        );
    }

    private static JobEvent jobEvent(String jobId, EventType eventType) {
        return jobEvent(jobId, eventType, Instant.parse("2024-01-01T00:00:00Z"));
    }
}
