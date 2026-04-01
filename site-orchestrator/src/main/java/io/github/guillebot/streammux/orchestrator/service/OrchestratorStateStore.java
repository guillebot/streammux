package io.github.guillebot.streammux.orchestrator.service;

import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OrchestratorStateStore {
    private final Map<String, JobDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, JobLease> leases = new ConcurrentHashMap<>();

    public void upsertDefinition(JobDefinition definition) {
        definitions.put(definition.jobId(), definition);
    }

    public Optional<JobDefinition> getDefinition(String jobId) {
        return Optional.ofNullable(definitions.get(jobId));
    }

    public Collection<JobDefinition> listDefinitions() {
        return definitions.values();
    }

    public void removeDefinition(String jobId) {
        definitions.remove(jobId);
    }

    public void upsertLease(JobLease lease) {
        leases.put(lease.jobId(), lease);
    }

    public Optional<JobLease> getLease(String jobId) {
        return Optional.ofNullable(leases.get(jobId));
    }

    public void removeLease(String jobId) {
        leases.remove(jobId);
    }
}
