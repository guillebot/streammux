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

    @Mock
    private RequestActorResolver actorResolver;

    private JobService newService() {
        return new JobService(stateStore, commandPublisher, topicValidationProperties(), actorResolver, new JobStatusResolver());
    }

    @Test
    void createJobNormalizesVersionAndPublishesDefinitionAndEvent() {
        JobService service = newService();
        JobDefinition input = jobDefinition("job-1", 99, DesiredJobState.ACTIVE, "alice");
        when(stateStore.getJob("job-1")).thenReturn(Optional.empty());
        when(actorResolver.currentActor()).thenReturn("alice");

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
        assertEquals("alice", eventCaptor.getValue().actor());
        assertEquals(created.jobId(), eventCaptor.getValue().jobId());
        assertEquals(created.jobVersion(), eventCaptor.getValue().jobVersion());
    }

    @Test
    void createJobRejectsExistingJobId() {
        JobService service = newService();
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
        JobService service = newService();
        JobDefinition current = jobDefinition("job-1", 4, DesiredJobState.ACTIVE, "alice");
        JobDefinition requested = jobDefinition("ignored", 0, DesiredJobState.PAUSED, "bob");
        when(stateStore.getJob("job-1")).thenReturn(Optional.of(current));
        when(actorResolver.currentActor()).thenReturn("bob");

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
        JobService service = newService();
        JobDefinition current = jobDefinition("job-1", 2, DesiredJobState.ACTIVE, "alice");
        when(stateStore.getJob("job-1")).thenReturn(Optional.of(current));
        when(actorResolver.currentActor()).thenReturn("operator");

        service.deleteJob("job-1");

        ArgumentCaptor<JobDefinition> definitionCaptor = ArgumentCaptor.forClass(JobDefinition.class);
        verify(commandPublisher).publishDefinition(definitionCaptor.capture());
        assertEquals(DesiredJobState.DELETED, definitionCaptor.getValue().desiredState());
        assertEquals(3, definitionCaptor.getValue().jobVersion());
        assertEquals("operator", definitionCaptor.getValue().updatedBy());

        ArgumentCaptor<JobEvent> eventCaptor = ArgumentCaptor.forClass(JobEvent.class);
        verify(commandPublisher).publishEvent(eventCaptor.capture());
        assertEquals(EventType.DELETED, eventCaptor.getValue().eventType());

        verify(stateStore).removeJob("job-1");
    }

    @Test
    void renameJobPublishesNewKeyDeletedSentinelAndPairedEvents() {
        JobService service = newService();
        JobDefinition current = jobDefinition("job-old", 5, DesiredJobState.ACTIVE, "alice");
        when(stateStore.getJob("job-old")).thenReturn(Optional.of(current));
        when(stateStore.getJob("job-new")).thenReturn(Optional.empty());
        when(actorResolver.currentActor()).thenReturn("operator");

        JobDefinition renamed = service.renameJob("job-old", "job-new");

        assertEquals("job-new", renamed.jobId());
        assertEquals(1, renamed.jobVersion());
        assertEquals(DesiredJobState.ACTIVE, renamed.desiredState());
        assertEquals("operator", renamed.updatedBy());
        // Preserved fields from current
        assertEquals(current.jobType(), renamed.jobType());
        assertEquals(current.priority(), renamed.priority());
        assertEquals(current.siteAffinity(), renamed.siteAffinity());
        assertEquals(current.routeAppConfig(), renamed.routeAppConfig());
        assertEquals(current.labels(), renamed.labels());
        assertEquals(current.tags(), renamed.tags());

        ArgumentCaptor<JobDefinition> definitionCaptor = ArgumentCaptor.forClass(JobDefinition.class);
        verify(commandPublisher, org.mockito.Mockito.times(2)).publishDefinition(definitionCaptor.capture());
        List<JobDefinition> published = definitionCaptor.getAllValues();
        JobDefinition publishedNew = published.get(0);
        JobDefinition publishedDeleted = published.get(1);
        assertEquals("job-new", publishedNew.jobId());
        assertEquals(1, publishedNew.jobVersion());
        assertEquals(DesiredJobState.ACTIVE, publishedNew.desiredState());
        assertEquals("job-old", publishedDeleted.jobId());
        assertEquals(6, publishedDeleted.jobVersion());
        assertEquals(DesiredJobState.DELETED, publishedDeleted.desiredState());

        ArgumentCaptor<JobEvent> eventCaptor = ArgumentCaptor.forClass(JobEvent.class);
        verify(commandPublisher, org.mockito.Mockito.times(2)).publishEvent(eventCaptor.capture());
        List<JobEvent> events = eventCaptor.getAllValues();
        JobEvent createdEvent = events.get(0);
        JobEvent deletedEvent = events.get(1);
        assertEquals(EventType.CREATED, createdEvent.eventType());
        assertEquals("job-new", createdEvent.jobId());
        assertEquals("job-old", createdEvent.attributes().get("renamedFrom"));
        assertEquals("rename", createdEvent.attributes().get("action"));
        assertEquals(EventType.DELETED, deletedEvent.eventType());
        assertEquals("job-old", deletedEvent.jobId());
        assertEquals("job-new", deletedEvent.attributes().get("renamedTo"));
        assertEquals("rename", deletedEvent.attributes().get("action"));

        verify(stateStore).removeJob("job-old");
        verify(stateStore).upsertDefinition(renamed);
    }

    @Test
    void renameJobRejectsMissingOldJob() {
        JobService service = newService();
        when(stateStore.getJob("job-missing")).thenReturn(Optional.empty());
        when(actorResolver.currentActor()).thenReturn("operator");

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.renameJob("job-missing", "job-new")
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(commandPublisher, never()).publishDefinition(any());
    }

    @Test
    void renameJobRejectsExistingNewJobId() {
        JobService service = newService();
        JobDefinition current = jobDefinition("job-old", 1, DesiredJobState.ACTIVE, "alice");
        when(stateStore.getJob("job-old")).thenReturn(Optional.of(current));
        when(stateStore.getJob("job-taken")).thenReturn(Optional.of(jobDefinition("job-taken", 1, DesiredJobState.ACTIVE, "bob")));
        when(actorResolver.currentActor()).thenReturn("operator");

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.renameJob("job-old", "job-taken")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(commandPublisher, never()).publishDefinition(any());
    }

    @Test
    void renameJobRejectsBlankNewJobId() {
        JobService service = newService();
        when(actorResolver.currentActor()).thenReturn("operator");

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.renameJob("job-old", "  ")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(commandPublisher, never()).publishDefinition(any());
    }

    @Test
    void renameJobRejectsNullNewJobId() {
        JobService service = newService();
        when(actorResolver.currentActor()).thenReturn("operator");

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.renameJob("job-old", null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(commandPublisher, never()).publishDefinition(any());
    }

    @Test
    void renameJobRejectsEqualNewJobId() {
        JobService service = newService();
        when(actorResolver.currentActor()).thenReturn("operator");

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.renameJob("job-old", "job-old")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(commandPublisher, never()).publishDefinition(any());
    }

    @Test
    void issueCommandPublishesMappedEventAndCommand() {
        JobService service = newService();
        JobDefinition current = jobDefinition("job-1", 6, DesiredJobState.ACTIVE, "alice");
        when(stateStore.getJob("job-1")).thenReturn(Optional.of(current));
        when(actorResolver.currentActor()).thenReturn("operator");

        service.issueCommand("job-1", CommandType.DRAIN);

        ArgumentCaptor<JobCommand> commandCaptor = ArgumentCaptor.forClass(JobCommand.class);
        verify(commandPublisher).publishCommand(commandCaptor.capture());
        assertEquals("job-1", commandCaptor.getValue().jobId());
        assertEquals(6, commandCaptor.getValue().jobVersion());
        assertEquals(CommandType.DRAIN, commandCaptor.getValue().commandType());
        assertEquals("operator", commandCaptor.getValue().issuedBy());

        ArgumentCaptor<JobEvent> eventCaptor = ArgumentCaptor.forClass(JobEvent.class);
        verify(stateStore).appendEvent(eventCaptor.capture());
        verify(commandPublisher).publishEvent(eventCaptor.getValue());
        assertEquals(EventType.RELEASED, eventCaptor.getValue().eventType());
        assertEquals("Command issued: DRAIN", eventCaptor.getValue().message());
    }

    @Test
    void validateAcceptsWellFormedDefinition() {
        JobService service = newService();
        JobDefinition definition = jobDefinition("job-1", 1, DesiredJobState.ACTIVE, "alice");

        service.validate(definition);

        verify(stateStore, never()).upsertDefinition(any());
        verify(commandPublisher, never()).publishDefinition(any());
    }

    @Test
    void validateRethrowsValidatorFailure() {
        JobService service = newService();
        JobDefinition definition = new JobDefinition(
            "job-1",
            1,
            JobType.ROUTE_APP,
            DesiredJobState.ACTIVE,
            5,
            "site-a",
            LeasePolicy.defaults(),
            1,
            new RouteAppConfig(
                "input-topic",
                PayloadFormat.JSON,
                PayloadFormat.JSON,
                null,
                List.of(new RouteDefinition("route-1", "malformed ==== value", "alerts")),
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

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.validate(definition));
        assertEquals(true, exception.getMessage().contains("routeAppConfig.routes[0].filterExpression invalid"));
        verify(stateStore, never()).upsertDefinition(any());
    }

    @Test
    void getJobThrowsNotFoundForMissingJob() {
        JobService service = newService();
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
            null,
            null,
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
