package io.github.guillebot.streammux.orchestrator.metrics;

import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.orchestrator.service.OrchestratorService;
import io.github.guillebot.streammux.orchestrator.service.OrchestratorStateStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class StreammuxOrchestratorMetrics {

    private final MeterRegistry registry;
    private final OrchestratorService orchestratorService;
    private final OrchestratorStateStore stateStore;
    private final Counter reconcileTotal;
    private final AtomicInteger ownedLeases = new AtomicInteger(0);

    private final Map<String, AtomicInteger> activeRunnersByType = new ConcurrentHashMap<>();
    private final Map<String, Gauge> activeRunnerGauges = new ConcurrentHashMap<>();
    private final Map<String, Counter> startFailureCounters = new ConcurrentHashMap<>();

    public StreammuxOrchestratorMetrics(
        MeterRegistry registry,
        @Lazy OrchestratorService orchestratorService,
        OrchestratorStateStore stateStore
    ) {
        this.registry = registry;
        this.orchestratorService = orchestratorService;
        this.stateStore = stateStore;
        this.reconcileTotal = Counter.builder("streammux.orchestrator.reconcile.total")
            .description("Reconcile loop iterations executed on this orchestrator instance")
            .register(registry);
        Gauge.builder("streammux.orchestrator.lease.owned", ownedLeases, AtomicInteger::get)
            .description("Leases currently owned and tracked by this orchestrator instance")
            .register(registry);
    }

    public void recordReconcile() {
        reconcileTotal.increment();
    }

    public void recordRunnerStartFailure(String jobId, JobType jobType) {
        String key = jobId + "|" + jobType.name();
        startFailureCounters.computeIfAbsent(key, ignored -> Counter.builder("streammux.orchestrator.runner.start_failures")
            .description("Runner start or restart failures on this orchestrator instance")
            .tags("job_id", jobId, "runner_type", jobType.name())
            .register(registry)).increment();
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 5_000)
    public void refreshRunnerGauges() {
        Map<JobType, Integer> counts = new EnumMap<>(JobType.class);
        for (String jobId : orchestratorService.activeJobIds()) {
            stateStore.getDefinition(jobId).ifPresent(definition ->
                counts.merge(definition.jobType(), 1, Integer::sum)
            );
        }

        Set<String> activeKeys = new HashSet<>();
        for (Map.Entry<JobType, Integer> entry : counts.entrySet()) {
            String key = entry.getKey().name();
            activeKeys.add(key);
            AtomicInteger holder = activeRunnersByType.computeIfAbsent(key, runnerType -> {
                AtomicInteger value = new AtomicInteger(0);
                Gauge gauge = Gauge.builder("streammux.orchestrator.active_runners", value, AtomicInteger::get)
                    .description("Active runners managed by this orchestrator instance")
                    .tags("runner_type", runnerType)
                    .register(registry);
                activeRunnerGauges.put(runnerType, gauge);
                return value;
            });
            holder.set(entry.getValue());
        }

        activeRunnersByType.keySet().removeIf(runnerType -> {
            if (activeKeys.contains(runnerType)) {
                return false;
            }
            Gauge gauge = activeRunnerGauges.remove(runnerType);
            if (gauge != null) {
                registry.remove(gauge);
            }
            return true;
        });

        ownedLeases.set(orchestratorService.activeJobIds().size());
    }
}
