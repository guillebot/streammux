package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.api.config.TopicValidationProperties;
import io.github.guillebot.streammux.contracts.command.JobCommand;
import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.CommandType;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.PayloadFormat;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private JobStateStore stateStore;

    @Mock
    private JobCommandPublisher commandPublisher;

    @Test
    void createJobNormalizesVersionAndPublishesDefinitionAndEvent() {
        JobService service = new JobService(stateStore, commandPublisher, topicValidationProperties());
        JobDefinition input = jobDefinition("job-1", 99, DesiredJobState.ACTIVE, "alice");
        when(stateStore.getJob("job-1")).thenReturn(Optional.empty());

        JobDefinition created = service.createJob(input);

        assertEquals(1, created.jobVersion());
        assertEquals("job-1", created.jobId());
        assertEquals("alice", created.updatedBy());
        assertNotNull(created.updatedAt());

        ArgumentCaptor<JobDefinition> definitionCaptor = ArgumentCaptor.forClass(JobDefinition.class);
        verify(stateStore).upsertDefinition(definitionCaptor.capture());
        verify(commandPublisher).publishDefinition(definitionCaptor.getValue());
        assertEquals(created, definitionCaptor.getValue());

        ArgumentCaptor<JobEvent> eventCaptor = ArgumentCaptor.forClass(JobEvent.class);
        verify(stateStore).appendEvent(eventCaptor.capture());
        verify(commandPublisher).publishEvent(eventCaptor.getValue());
        assertEquals(EventType.CREATED, eventCaptor.getValue().eventType());
        assertEquals(created.jobId(), eventCaptor.getValue().jobId());
        assertEquals(created.jobVersion(), eventCaptor.getValue().jobVersion());
    }

    @Test
    void createJobRejectsExistingJobId() {
        JobService service = new JobService(stateStore, commandPublisher, topicValidationProperties());
        when(stateStore.getJob("job-1")).thenReturn(Optional.of(jobDefinition("job-1", 1, DesiredJobState.ACTIVE, "alice")));

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.createJob(jobDefinition("job-1", 1, DesiredJobState.ACTIVE, "bob"))
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(commandPublisher, never()).publishDefinition(any());
    }

    @Test
    void updateJobBumpsVersionAndPublishesUpdatedEvent() {
        JobService service = new JobService(stateStore, commandPublisher, topicValidationProperties());
        JobDefinition current = jobDefinition("job-1", 4, DesiredJobState.ACTIVE, "alice");
        JobDefinition requested = jobDefinition("ignored", 0, DesiredJobState.PAUSED, "bob");
        when(stateStore.getJob("job-1")).thenReturn(Optional.of(current));

        JobDefinition updated = service.updateJob("job-1", requested);

        assertEquals(5, updated.jobVersion());
        assertEquals("job-1", updated.jobId());
        assertEquals(DesiredJobState.PAUSED, updated.desiredState());
        assertEquals("bob", updated.updatedBy());

        ArgumentCaptor<JobEvent> eventCaptor = ArgumentCaptor.forClass(JobEvent.class);
        verify(commandPublisher).publishEvent(eventCaptor.capture());
        assertEquals(EventType.UPDATED, eventCaptor.getValue().eventType());
        assertEquals(5, eventCaptor.getValue().jobVersion());
    }

    @Test
    void deleteJobPublishesDeletedDefinitionAndRemovesStoredState() {
        JobService service = new JobService(stateStore, commandPublisher, topicValidationProperties());
        JobDefinition current = jobDefinition("job-1", 2, DesiredJobState.ACTIVE, "alice");
        when(stateStore.getJob("job-1")).thenReturn(Optional.of(current));

        service.deleteJob("job-1");

        ArgumentCaptor<JobDefinition> definitionCaptor = ArgumentCaptor.forClass(JobDefinition.class);
        verify(commandPublisher).publishDefinition(definitionCaptor.capture());
        assertEquals(DesiredJobState.DELETED, definitionCaptor.getValue().desiredState());
        assertEquals(3, definitionCaptor.getValue().jobVersion());
        assertEquals("job-management-api", definitionCaptor.getValue().updatedBy());

        ArgumentCaptor<JobEvent> eventCaptor = ArgumentCaptor.forClass(JobEvent.class);
        verify(commandPublisher).publishEvent(eventCaptor.capture());
        assertEquals(EventType.DELETED, eventCaptor.getValue().eventType());

        verify(stateStore).removeJob("job-1");
    }

    @Test
    void issueCommandPublishesMappedEventAndCommand() {
        JobService service = new JobService(stateStore, commandPublisher, topicValidationProperties());
        JobDefinition current = jobDefinition("job-1", 6, DesiredJobState.ACTIVE, "alice");
        when(stateStore.getJob("job-1")).thenReturn(Optional.of(current));

        service.issueCommand("job-1", CommandType.DRAIN);

        ArgumentCaptor<JobCommand> commandCaptor = ArgumentCaptor.forClass(JobCommand.class);
        verify(commandPublisher).publishCommand(commandCaptor.capture());
        assertEquals("job-1", commandCaptor.getValue().jobId());
        assertEquals(6, commandCaptor.getValue().jobVersion());
        assertEquals(CommandType.DRAIN, commandCaptor.getValue().commandType());
        assertEquals("api", commandCaptor.getValue().issuedBy());

        ArgumentCaptor<JobEvent> eventCaptor = ArgumentCaptor.forClass(JobEvent.class);
        verify(stateStore).appendEvent(eventCaptor.capture());
        verify(commandPublisher).publishEvent(eventCaptor.getValue());
        assertEquals(EventType.RELEASED, eventCaptor.getValue().eventType());
        assertEquals("Command issued: DRAIN", eventCaptor.getValue().message());
    }

    @Test
    void getJobThrowsNotFoundForMissingJob() {
        JobService service = new JobService(stateStore, commandPublisher, topicValidationProperties());
        when(stateStore.getJob("missing")).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.getJob("missing"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    private static JobDefinition jobDefinition(String jobId, long version, DesiredJobState desiredState, String updatedBy) {
        return new JobDefinition(
            jobId,
            version,
            JobType.ROUTE_APP,
            desiredState,
            5,
            "site-a",
            LeasePolicy.defaults(),
            1,
            new RouteAppConfig(
                "input-topic",
                PayloadFormat.JSON,
                PayloadFormat.JSON,
                null,
                List.of(new RouteDefinition("route-1", "message.type == \"ALARM\"", "alerts")),
                Map.of(),
                Map.of()
            ),
            Map.of("team", "mux"),
            List.of("test"),
            Instant.parse("2024-01-01T00:00:00Z"),
            updatedBy
        );
    }

    private static TopicValidationProperties topicValidationProperties() {
        return new TopicValidationProperties(
            List.of("input-topic"),
            List.of(),
            List.of("alerts"),
            List.of()
        );
    }
}
