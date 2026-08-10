package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JobStateStore {
    private static final int GLOBAL_EVENT_LIMIT = 1000;

    private final Map<String, JobDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, JobLease> leases = new ConcurrentHashMap<>();
    private final Map<String, JobRuntimeStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, List<JobEvent>> events = new ConcurrentHashMap<>();
    private final Deque<JobEvent> recentEvents = new ArrayDeque<>();

    public Collection<JobDefinition> listJobs() { return definitions.values().stream().sorted(Comparator.comparing(JobDefinition::jobId)).toList(); }
    public Optional<JobDefinition> getJob(String jobId) { return Optional.ofNullable(definitions.get(jobId)); }
    public Optional<JobLease> getLease(String jobId) { return Optional.ofNullable(leases.get(jobId)); }
    public Optional<JobRuntimeStatus> getStatus(String jobId) { return Optional.ofNullable(statuses.get(jobId)); }
    public List<JobEvent> getEvents(String jobId) { return events.getOrDefault(jobId, List.of()); }
    public void upsertDefinition(JobDefinition definition) { definitions.put(definition.jobId(), definition); }
    public void removeDefinition(String jobId) { definitions.remove(jobId); }
    public void upsertLease(JobLease lease) { leases.put(lease.jobId(), lease); }
    public void removeLease(String jobId) { leases.remove(jobId); }
    public void upsertStatus(JobRuntimeStatus status) { statuses.put(status.jobId(), status); }
    public void removeStatus(String jobId) { statuses.remove(jobId); }

    public void appendEvent(JobEvent event) {
        events.computeIfAbsent(event.jobId(), ignored -> new ArrayList<>()).add(event);
        synchronized (recentEvents) {
            recentEvents.addLast(event);
            while (recentEvents.size() > GLOBAL_EVENT_LIMIT) {
                recentEvents.removeFirst();
            }
        }
    }

    public List<JobEvent> listRecentEvents(int limit, String jobId, Collection<EventType> eventTypes, String actor) {
        int effectiveLimit = Math.max(1, Math.min(limit, GLOBAL_EVENT_LIMIT));
        synchronized (recentEvents) {
            return recentEvents.stream()
                .filter(event -> containsIgnoreCase(event.jobId(), jobId))
                .filter(event -> eventTypes == null || eventTypes.isEmpty() || eventTypes.contains(event.eventType()))
                .filter(event -> containsIgnoreCase(event.actor(), actor))
                .sorted(Comparator.comparing(JobEvent::eventTime).reversed())
                .limit(effectiveLimit)
                .toList();
        }
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (needle == null || needle.isBlank()) {
            return true;
        }
        if (haystack == null) {
            return false;
        }
        return haystack.toLowerCase().contains(needle.trim().toLowerCase());
    }

    public void removeEvents(String jobId) { events.remove(jobId); }
    public void removeJob(String jobId) {
        definitions.remove(jobId);
        leases.remove(jobId);
        statuses.remove(jobId);
        events.remove(jobId);
    }

    public int snapshotLeaseCount() { return leases.size(); }
    public int snapshotStatusCount() { return statuses.size(); }
    public int snapshotEventJobCount() { return events.size(); }
}
