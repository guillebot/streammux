package io.github.guillebot.streammux.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaJobStateProjectorTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void removesJobOnDefinitionTombstone() {
        JobStateStore store = new JobStateStore();
        store.upsertDefinition(jobDefinition("job-1", DesiredJobState.ACTIVE));
        KafkaJobStateProjector projector = new KafkaJobStateProjector(store);

        projector.onJobDefinition(new ConsumerRecord<>("job-definitions", 0, 0L, "job-1", null));

        assertTrue(store.getJob("job-1").isEmpty());
    }

    @Test
    void removesDeletedDefinitionsInsteadOfKeepingThem() throws Exception {
        JobStateStore store = new JobStateStore();
        store.upsertDefinition(jobDefinition("job-1", DesiredJobState.ACTIVE));
        KafkaJobStateProjector projector = new KafkaJobStateProjector(store);

        projector.onJobDefinition(record("job-definitions", "job-1", jobDefinition("job-1", DesiredJobState.DELETED)));

        assertTrue(store.getJob("job-1").isEmpty());
    }

    @Test
    void projectsLeaseStatusAndEventRecords() throws Exception {
        JobStateStore store = new JobStateStore();
        KafkaJobStateProjector projector = new KafkaJobStateProjector(store);

        JobLease lease = new JobLease("job-1", 2, "site-a", "instance-a", 3, LeaseStatus.RUNNING, Instant.parse("2024-01-01T00:01:00Z"), Instant.parse("2024-01-01T00:00:30Z"));
        JobRuntimeStatus status = new JobRuntimeStatus(
            "job-1",
            2,
            RuntimeState.RUNNING,
            HealthState.HEALTHY,
            Instant.parse("2024-01-01T00:00:30Z"),
            new WorkerMetadata("worker-1", "route-app", "RUNNING", Map.of()),
            null,
            new LagMetrics(0, 12, 34)
        );
        JobEvent event = new JobEvent("event-1", "job-1", 2, EventType.STARTED, Instant.parse("2024-01-01T00:00:20Z"), "site-a", "instance-a", "Started", Map.of());

        projector.onJobLease(record("job-leases", "job-1", lease));
        projector.onJobStatus(record("job-status", "job-1", status));
        projector.onJobEvent(record("job-events", "job-1", event));

        assertEquals(lease, store.getLease("job-1").orElseThrow());
        assertEquals(status, store.getStatus("job-1").orElseThrow());
        assertEquals(List.of(event), store.getEvents("job-1"));
    }

    @Test
    void throwsOnUnreadablePayload() {
        KafkaJobStateProjector projector = new KafkaJobStateProjector(new JobStateStore());

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> projector.onJobDefinition(new ConsumerRecord<>("job-definitions", 0, 0L, "job-1", "not-json".getBytes()))
        );

        assertTrue(exception.getMessage().contains("Failed to deserialize JobDefinition"));
    }

    private static ConsumerRecord<String, byte[]> record(String topic, String key, Object value) throws Exception {
        return new ConsumerRecord<>(topic, 0, 0L, key, OBJECT_MAPPER.writeValueAsBytes(value));
    }

    private static JobDefinition jobDefinition(String jobId, DesiredJobState desiredState) {
        return new JobDefinition(
            jobId,
            2,
            JobType.ROUTE_APP,
            desiredState,
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
}
