package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.EventType;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ActivityService {
    private final JobStateStore stateStore;
    private final JobCommandPublisher commandPublisher;
    private final RequestActorResolver actorResolver;

    public ActivityService(JobStateStore stateStore, JobCommandPublisher commandPublisher, RequestActorResolver actorResolver) {
        this.stateStore = stateStore;
        this.commandPublisher = commandPublisher;
        this.actorResolver = actorResolver;
    }

    public String currentActor() {
        return actorResolver.currentActor();
    }

    public List<JobEvent> listActivity(int limit, String jobId, List<EventType> eventTypes, String actor) {
        return stateStore.listRecentEvents(limit, jobId, eventTypes, actor);
    }

    public JobEvent recordSession() {
        String actor = actorResolver.currentActor();
        JobEvent event = new JobEvent(
            UUID.randomUUID().toString(),
            JobEvent.PLATFORM_JOB_ID,
            0,
            EventType.SESSION,
            Instant.now(),
            null,
            "job-management-api",
            "Console session",
            Map.of(),
            actor
        );
        stateStore.appendEvent(event);
        commandPublisher.publishEvent(event);
        return event;
    }
}
