package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.EventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityServiceTest {

    @Mock
    private JobStateStore stateStore;

    @Mock
    private JobCommandPublisher commandPublisher;

    @Mock
    private RequestActorResolver actorResolver;

    @Test
    void recordSessionPublishesPlatformSessionEventWithActor() {
        when(actorResolver.currentActor()).thenReturn("jsolarin");
        ActivityService service = new ActivityService(stateStore, commandPublisher, actorResolver);

        JobEvent recorded = service.recordSession();

        assertEquals(JobEvent.PLATFORM_JOB_ID, recorded.jobId());
        assertEquals(EventType.SESSION, recorded.eventType());
        assertEquals("jsolarin", recorded.actor());
        assertEquals("Console session", recorded.message());

        ArgumentCaptor<JobEvent> eventCaptor = ArgumentCaptor.forClass(JobEvent.class);
        verify(stateStore).appendEvent(eventCaptor.capture());
        verify(commandPublisher).publishEvent(eventCaptor.getValue());
        assertEquals(EventType.SESSION, eventCaptor.getValue().eventType());
    }

    @Test
    void listActivityDelegatesToStateStore() {
        JobEvent event = new JobEvent(
            "evt-1",
            "job-a",
            1,
            EventType.UPDATED,
            Instant.parse("2024-01-01T00:00:00Z"),
            null,
            "job-management-api",
            "updated",
            Map.of(),
            "operator"
        );
        when(stateStore.listRecentEvents(50, "job-a", EventType.UPDATED, "operator")).thenReturn(List.of(event));
        ActivityService service = new ActivityService(stateStore, commandPublisher, actorResolver);

        List<JobEvent> result = service.listActivity(50, "job-a", EventType.UPDATED, "operator");

        assertEquals(1, result.size());
        assertEquals("job-a", result.getFirst().jobId());
    }

    @Test
    void currentActorDelegatesToResolver() {
        when(actorResolver.currentActor()).thenReturn("streammux");
        ActivityService service = new ActivityService(stateStore, commandPublisher, actorResolver);

        assertEquals("streammux", service.currentActor());
    }
}
