package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.api.config.TopicValidationProperties;
import io.github.guillebot.streammux.contracts.command.JobCommand;
import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.CommandType;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.validation.JobDefinitionValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {
    private static final Logger LOGGER = LoggerFactory.getLogger(JobService.class);

    private final JobStateStore stateStore;
    private final JobCommandPublisher commandPublisher;
    private final TopicValidationProperties topicValidationProperties;
    private final RequestActorResolver actorResolver;
    private final JobStatusResolver statusResolver;

    public JobService(
        JobStateStore stateStore,
        JobCommandPublisher commandPublisher,
        TopicValidationProperties topicValidationProperties,
        RequestActorResolver actorResolver,
        JobStatusResolver statusResolver
    ) {
        this.stateStore = stateStore;
        this.commandPublisher = commandPublisher;
        this.topicValidationProperties = topicValidationProperties;
        this.actorResolver = actorResolver;
        this.statusResolver = statusResolver;
    }

    public Collection<JobDefinition> listJobs() { return stateStore.listJobs(); }
    public JobDefinition getJob(String jobId) { return stateStore.getJob(jobId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobId)); }
    public Optional<JobLease> getLease(String jobId) { return stateStore.getLease(jobId); }
    public Optional<JobRuntimeStatus> getStatus(String jobId) {
        return statusResolver.resolve(
            stateStore.getStatus(jobId),
            stateStore.getJob(jobId),
            stateStore.getLease(jobId)
        );
    }
    public List<JobEvent> getEvents(String jobId) { return stateStore.getEvents(jobId); }

    /**
     * Runs the same validator used by create/update/rename without touching the state store or
     * Kafka. Throws {@link IllegalArgumentException} on failure so the shared exception handler
     * returns a {@code 400 VALIDATION_ERROR} response identical to the ones a real save produces.
     */
    public void validate(JobDefinition definition) {
        JobDefinitionValidator.validate(definition, topicValidationProperties.toPolicy());
    }

    public JobDefinition createJob(JobDefinition definition) {
        String actor = actorResolver.currentActor();
        JobDefinitionValidator.validate(definition, topicValidationProperties.toPolicy());
        if (stateStore.getJob(definition.jobId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job already exists: " + definition.jobId());
        }
        JobDefinition normalized = new JobDefinition(
            definition.jobId(),
            1,
            definition.jobType(),
            definition.desiredState(),
            definition.priority(),
            definition.siteAffinity(),
            definition.leasePolicy(),
            definition.parallelism(),
            definition.routeAppConfig(),
            definition.randomSamplerConfig(),
            definition.alarmsToZtrConfig(),
            definition.labels(),
            definition.tags(),
            Instant.now(),
            actor
        );
        stateStore.upsertDefinition(normalized);
        commandPublisher.publishDefinition(normalized);
        JobEvent createdEvent = newEvent(normalized.jobId(), normalized.jobVersion(), EventType.CREATED, actor, "Job created", Map.of("desiredState", normalized.desiredState().name()));
        appendAndPublish(createdEvent);
        auditLog("job.create", normalized.jobId(), actor, Map.of("desiredState", normalized.desiredState().name()));
        return normalized;
    }

    public JobDefinition updateJob(String jobId, JobDefinition definition) {
        String actor = actorResolver.currentActor();
        JobDefinition current = getJob(jobId);
        JobDefinitionValidator.validate(definition, topicValidationProperties.toPolicy());
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("previousDesiredState", current.desiredState().name());
        attributes.put("desiredState", definition.desiredState().name());
        JobDefinition updated = new JobDefinition(
            jobId,
            current.jobVersion() + 1,
            definition.jobType(),
            definition.desiredState(),
            definition.priority(),
            definition.siteAffinity(),
            definition.leasePolicy(),
            definition.parallelism(),
            definition.routeAppConfig(),
            definition.randomSamplerConfig(),
            definition.alarmsToZtrConfig(),
            definition.labels(),
            definition.tags(),
            Instant.now(),
            actor
        );
        stateStore.upsertDefinition(updated);
        commandPublisher.publishDefinition(updated);
        String message = current.desiredState() != updated.desiredState()
            ? "Job updated (desiredState " + current.desiredState() + " -> " + updated.desiredState() + ")"
            : "Job updated";
        JobEvent updatedEvent = newEvent(jobId, updated.jobVersion(), EventType.UPDATED, actor, message, attributes);
        appendAndPublish(updatedEvent);
        auditLog("job.update", jobId, actor, attributes);
        return updated;
    }

    public JobDefinition renameJob(String oldJobId, String newJobId) {
        String actor = actorResolver.currentActor();
        if (newJobId == null || newJobId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "newJobId is required");
        }
        String trimmedNewJobId = newJobId.trim();
        if (trimmedNewJobId.equals(oldJobId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "newJobId must differ from the current jobId; use PUT /jobs/{jobId} to update other fields");
        }
        JobDefinition current = getJob(oldJobId);
        if (stateStore.getJob(trimmedNewJobId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job already exists: " + trimmedNewJobId);
        }
        JobDefinition renamed = new JobDefinition(
            trimmedNewJobId,
            1,
            current.jobType(),
            current.desiredState(),
            current.priority(),
            current.siteAffinity(),
            current.leasePolicy(),
            current.parallelism(),
            current.routeAppConfig(),
            current.randomSamplerConfig(),
            current.alarmsToZtrConfig(),
            current.labels(),
            current.tags(),
            Instant.now(),
            actor
        );
        // Defensive re-validation: the current record already validated when it was saved, but the
        // configured allowlists may have tightened since then, and the new jobId itself is user input.
        JobDefinitionValidator.validate(renamed, topicValidationProperties.toPolicy());
        JobDefinition deletedOld = new JobDefinition(
            oldJobId,
            current.jobVersion() + 1,
            current.jobType(),
            DesiredJobState.DELETED,
            current.priority(),
            current.siteAffinity(),
            current.leasePolicy(),
            current.parallelism(),
            current.routeAppConfig(),
            current.randomSamplerConfig(),
            current.alarmsToZtrConfig(),
            current.labels(),
            current.tags(),
            Instant.now(),
            actor
        );
        commandPublisher.publishDefinition(renamed);
        commandPublisher.publishDefinition(deletedOld);
        Map<String, Object> renamedFromAttrs = new HashMap<>();
        renamedFromAttrs.put("action", "rename");
        renamedFromAttrs.put("renamedFrom", oldJobId);
        renamedFromAttrs.put("previousVersion", current.jobVersion());
        JobEvent createdEvent = newEvent(trimmedNewJobId, renamed.jobVersion(), EventType.CREATED, actor, "Job renamed from " + oldJobId, renamedFromAttrs);
        JobEvent deletedEvent = newEvent(oldJobId, deletedOld.jobVersion(), EventType.DELETED, actor, "Job renamed to " + trimmedNewJobId, Map.of("action", "rename", "renamedTo", trimmedNewJobId));
        appendAndPublish(createdEvent);
        appendAndPublish(deletedEvent);
        stateStore.removeJob(oldJobId);
        stateStore.upsertDefinition(renamed);
        auditLog("job.rename", trimmedNewJobId, actor, Map.of("previousJobId", oldJobId, "previousVersion", current.jobVersion()));
        return renamed;
    }

    public void deleteJob(String jobId) {
        String actor = actorResolver.currentActor();
        JobDefinition current = getJob(jobId);
        JobDefinition deleted = new JobDefinition(
            jobId,
            current.jobVersion() + 1,
            current.jobType(),
            DesiredJobState.DELETED,
            current.priority(),
            current.siteAffinity(),
            current.leasePolicy(),
            current.parallelism(),
            current.routeAppConfig(),
            current.randomSamplerConfig(),
            current.alarmsToZtrConfig(),
            current.labels(),
            current.tags(),
            Instant.now(),
            actor
        );
        commandPublisher.publishDefinition(deleted);
        JobEvent deletedEvent = newEvent(jobId, deleted.jobVersion(), EventType.DELETED, actor, "Job deleted", Map.of());
        appendAndPublish(deletedEvent);
        stateStore.removeJob(jobId);
        auditLog("job.delete", jobId, actor, Map.of());
    }

    public void issueCommand(String jobId, CommandType commandType) {
        String actor = actorResolver.currentActor();
        JobDefinition job = getJob(jobId);
        JobCommand command = new JobCommand(UUID.randomUUID().toString(), jobId, job.jobVersion(), commandType, Instant.now(), actor, Map.of());
        commandPublisher.publishCommand(command);
        JobEvent event = newEvent(jobId, job.jobVersion(), mapEventType(commandType), actor, "Command issued: " + commandType, Map.of("commandType", commandType.name()));
        appendAndPublish(event);
        auditLog("job.command", jobId, actor, Map.of("commandType", commandType.name()));
    }

    private JobEvent newEvent(String jobId, long version, EventType type, String actor, String message, Map<String, Object> attributes) {
        return new JobEvent(
            UUID.randomUUID().toString(),
            jobId,
            version,
            type,
            Instant.now(),
            null,
            "job-management-api",
            message,
            attributes,
            actor
        );
    }

    private void appendAndPublish(JobEvent event) {
        stateStore.appendEvent(event);
        commandPublisher.publishEvent(event);
    }

    private void auditLog(String action, String jobId, String actor, Map<String, Object> fields) {
        LOGGER.info("action={} jobId={} actor={} {}", action, jobId, actor, fields);
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
