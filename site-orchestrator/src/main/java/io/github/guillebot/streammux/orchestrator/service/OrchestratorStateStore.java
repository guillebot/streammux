package io.github.guillebot.streammux.orchestrator.service;

import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * Stores {@code lease} unless a higher epoch is already present for the job.
     * Same-epoch updates (heartbeats) replace the stored record.
     *
     * @return {@code true} if the incoming lease was stored
     */
    public boolean upsertLease(JobLease lease) {
        AtomicBoolean stored = new AtomicBoolean(false);
        leases.compute(lease.jobId(), (ignored, existing) -> {
            if (existing != null && existing.leaseEpoch() > lease.leaseEpoch()) {
                return existing;
            }
            stored.set(true);
            return lease;
        });
        return stored.get();
    }

    public Optional<JobLease> getLease(String jobId) {
        return Optional.ofNullable(leases.get(jobId));
    }

    public void removeLease(String jobId) {
        leases.remove(jobId);
    }
}
