package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JobStateStore {
    private final Map<String, JobDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, JobLease> leases = new ConcurrentHashMap<>();
    private final Map<String, JobRuntimeStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, List<JobEvent>> events = new ConcurrentHashMap<>();

    public Collection<JobDefinition> listJobs() { return definitions.values().stream().sorted(Comparator.comparing(JobDefinition::jobId)).toList(); }
    public Optional<JobDefinition> getJob(String jobId) { return Optional.ofNullable(definitions.get(jobId)); }
    public Optional<JobLease> getLease(String jobId) { return Optional.ofNullable(leases.get(jobId)); }
    public Optional<JobRuntimeStatus> getStatus(String jobId) { return Optional.ofNullable(statuses.get(jobId)); }
    public List<JobEvent> getEvents(String jobId) { return events.getOrDefault(jobId, List.of()); }
    public void upsertDefinition(JobDefinition definition) { definitions.put(definition.jobId(), definition); }
    public void upsertLease(JobLease lease) { leases.put(lease.jobId(), lease); }
    public void upsertStatus(JobRuntimeStatus status) { statuses.put(status.jobId(), status); }
    public void appendEvent(JobEvent event) { events.computeIfAbsent(event.jobId(), ignored -> new ArrayList<>()).add(event); }
}
