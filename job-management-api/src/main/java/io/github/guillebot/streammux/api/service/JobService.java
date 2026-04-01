package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.contracts.command.JobCommand;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.CommandType;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.validation.JobDefinitionValidator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {
    private final JobStateStore stateStore;
    private final JobCommandPublisher commandPublisher;

    public JobService(JobStateStore stateStore, JobCommandPublisher commandPublisher) {
        this.stateStore = stateStore;
        this.commandPublisher = commandPublisher;
    }

    public Collection<JobDefinition> listJobs() { return stateStore.listJobs(); }
    public JobDefinition getJob(String jobId) { return stateStore.getJob(jobId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobId)); }
    public Optional<JobLease> getLease(String jobId) { return stateStore.getLease(jobId); }
    public Optional<JobRuntimeStatus> getStatus(String jobId) { return stateStore.getStatus(jobId); }
    public List<JobEvent> getEvents(String jobId) { return stateStore.getEvents(jobId); }

    public JobDefinition createJob(JobDefinition definition) {
        JobDefinitionValidator.validate(definition);
        if (stateStore.getJob(definition.jobId()).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Job already exists: " + definition.jobId());
        JobDefinition normalized = new JobDefinition(definition.jobId(), 1, definition.jobType(), definition.desiredState(), definition.priority(), definition.siteAffinity(), definition.leasePolicy(), definition.parallelism(), definition.routeAppConfig(), definition.labels(), definition.tags(), Instant.now(), definition.updatedBy());
        stateStore.upsertDefinition(normalized);
        commandPublisher.publishDefinition(normalized);
        JobEvent createdEvent = newEvent(normalized.jobId(), normalized.jobVersion(), EventType.CREATED, "Job created");
        stateStore.appendEvent(createdEvent);
        commandPublisher.publishEvent(createdEvent);
        return normalized;
    }

    public JobDefinition updateJob(String jobId, JobDefinition definition) {
        JobDefinition current = getJob(jobId);
        JobDefinitionValidator.validate(definition);
        JobDefinition updated = new JobDefinition(jobId, current.jobVersion() + 1, definition.jobType(), definition.desiredState(), definition.priority(), definition.siteAffinity(), definition.leasePolicy(), definition.parallelism(), definition.routeAppConfig(), definition.labels(), definition.tags(), Instant.now(), definition.updatedBy());
        stateStore.upsertDefinition(updated);
        commandPublisher.publishDefinition(updated);
        JobEvent updatedEvent = newEvent(jobId, updated.jobVersion(), EventType.UPDATED, "Job updated");
        stateStore.appendEvent(updatedEvent);
        commandPublisher.publishEvent(updatedEvent);
        return updated;
    }

    public void issueCommand(String jobId, CommandType commandType) {
        JobDefinition job = getJob(jobId);
        JobCommand command = new JobCommand(UUID.randomUUID().toString(), jobId, job.jobVersion(), commandType, Instant.now(), "api", Map.of());
        commandPublisher.publishCommand(command);
        JobEvent event = newEvent(jobId, job.jobVersion(), mapEventType(commandType), "Command issued: " + commandType);
        stateStore.appendEvent(event);
        commandPublisher.publishEvent(event);
    }

    private JobEvent newEvent(String jobId, long version, EventType type, String message) {
        return new JobEvent(UUID.randomUUID().toString(), jobId, version, type, Instant.now(), null, "job-management-api", message, Map.of());
    }

    private EventType mapEventType(CommandType commandType) {
        return switch (commandType) {
            case PAUSE -> EventType.PAUSED;
            case RESUME -> EventType.RESUMED;
            case RESTART -> EventType.STARTED;
            case DRAIN -> EventType.RELEASED;
            case DELETE -> EventType.DELETED;
        };
    }
}
