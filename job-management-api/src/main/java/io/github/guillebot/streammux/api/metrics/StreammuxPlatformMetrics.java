package io.github.guillebot.streammux.api.metrics;

import io.github.guillebot.streammux.api.service.JobStateStore;
import io.github.guillebot.streammux.api.service.JobStatusResolver;
import io.github.guillebot.streammux.api.service.PlatformHealthService;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LagMetrics;
import io.github.guillebot.streammux.contracts.model.RuntimeState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class StreammuxPlatformMetrics {

    private final MeterRegistry registry;
    private final JobStateStore stateStore;
    private final PlatformHealthService platformHealthService;
    private final JobStatusResolver statusResolver;

    private final AtomicInteger platformKafkaUp = new AtomicInteger(0);
    private final AtomicInteger readModelJobs = new AtomicInteger(0);
    private final AtomicInteger readModelLeases = new AtomicInteger(0);
    private final AtomicInteger readModelStatuses = new AtomicInteger(0);

    private final Map<String, AtomicLong> jobInputLag = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> jobInputRate = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> jobInputCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> jobOutputRate = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> jobOutputCount = new ConcurrentHashMap<>();
    private final Map<String, Gauge> jobLagGauges = new ConcurrentHashMap<>();
    private final Map<String, Gauge> jobInputRateGauges = new ConcurrentHashMap<>();
    private final Map<String, Gauge> jobInputCountGauges = new ConcurrentHashMap<>();
    private final Map<String, Gauge> jobRateGauges = new ConcurrentHashMap<>();
    private final Map<String, Gauge> jobOutputCountGauges = new ConcurrentHashMap<>();
    private final Map<String, Gauge> leaseHolderGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> leaseHolderValues = new ConcurrentHashMap<>();

    private final Map<String, Gauge> configuredGauges = new ConcurrentHashMap<>();
    private final Map<String, Gauge> runtimeGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> configuredValues = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> runtimeValues = new ConcurrentHashMap<>();

    public StreammuxPlatformMetrics(
        MeterRegistry registry,
        JobStateStore stateStore,
        PlatformHealthService platformHealthService,
        JobStatusResolver statusResolver
    ) {
        this.registry = registry;
        this.stateStore = stateStore;
        this.platformHealthService = platformHealthService;
        this.statusResolver = statusResolver;

        Gauge.builder("streammux.platform.kafka.up", platformKafkaUp, AtomicInteger::get)
            .description("1 when the management API can reach the configured Kafka cluster")
            .register(registry);
        Gauge.builder("streammux.read_model.jobs", readModelJobs, AtomicInteger::get)
            .description("Configured job definitions in the in-memory read model")
            .register(registry);
        Gauge.builder("streammux.read_model.leases", readModelLeases, AtomicInteger::get)
            .description("Job leases in the in-memory read model")
            .register(registry);
        Gauge.builder("streammux.read_model.statuses", readModelStatuses, AtomicInteger::get)
            .description("Job runtime statuses in the in-memory read model")
            .register(registry);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void refreshPlatformHealth() {
        PlatformHealthService.PlatformHealth health = platformHealthService.snapshot();
        platformKafkaUp.set("UP".equals(health.kafka().status()) ? 1 : 0);
        readModelJobs.set(health.readModel().jobCount());
        readModelLeases.set(health.readModel().leaseCount());
        readModelStatuses.set(health.readModel().statusCount());
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 5_000)
    public void refreshJobMetrics() {
        Map<String, Integer> configuredRollup = new HashMap<>();
        for (JobDefinition definition : stateStore.listJobs()) {
            String key = configuredKey(definition.desiredState(), definition.jobType());
            configuredRollup.merge(key, 1, Integer::sum);
        }
        syncRollupGauges(
            configuredRollup,
            configuredValues,
            configuredGauges,
            "streammux.jobs.configured",
            "Configured jobs by desired state and job type",
            this::configuredTags
        );

        Map<String, Integer> runtimeRollup = new HashMap<>();
        Set<String> activeJobIds = new HashSet<>();
        for (JobDefinition definition : stateStore.listJobs()) {
            Optional<JobRuntimeStatus> status = statusResolver.resolve(
                stateStore.getStatus(definition.jobId()),
                Optional.of(definition),
                stateStore.getLease(definition.jobId())
            );
            if (status.isEmpty()) {
                continue;
            }
            JobRuntimeStatus runtimeStatus = status.get();
            String key = runtimeKey(runtimeStatus.state(), runtimeStatus.health());
            runtimeRollup.merge(key, 1, Integer::sum);
            activeJobIds.add(definition.jobId());

            AtomicLong lagHolder = jobInputLag.computeIfAbsent(
                definition.jobId(),
                jobId -> registerJobLagGauge(jobId, definition.jobType())
            );
            AtomicLong inputRateHolder = jobInputRate.computeIfAbsent(
                definition.jobId(),
                jobId -> registerJobInputRateGauge(jobId, definition.jobType())
            );
            AtomicLong inputCountHolder = jobInputCount.computeIfAbsent(
                definition.jobId(),
                jobId -> registerJobInputCountGauge(jobId, definition.jobType())
            );
            AtomicLong outputRateHolder = jobOutputRate.computeIfAbsent(
                definition.jobId(),
                jobId -> registerJobOutputRateGauge(jobId, definition.jobType())
            );
            AtomicLong outputCountHolder = jobOutputCount.computeIfAbsent(
                definition.jobId(),
                jobId -> registerJobOutputCountGauge(jobId, definition.jobType())
            );
            LagMetrics lagMetrics = runtimeStatus.lagMetrics();
            if (lagMetrics != null) {
                lagHolder.set(lagMetrics.inputLag());
                inputRateHolder.set(lagMetrics.inputRatePerSecond());
                inputCountHolder.set(lagMetrics.inputCount());
                outputRateHolder.set(lagMetrics.outputRatePerSecond());
                outputCountHolder.set(lagMetrics.outputCount());
            } else {
                lagHolder.set(0L);
                inputRateHolder.set(0L);
                inputCountHolder.set(0L);
                outputRateHolder.set(0L);
                outputCountHolder.set(0L);
            }
        }
        removeStaleJobSeries(activeJobIds);

        syncRollupGauges(
            runtimeRollup,
            runtimeValues,
            runtimeGauges,
            "streammux.jobs.runtime",
            "Running jobs by runtime state and health",
            this::runtimeTags
        );

        Set<String> activeLeaseKeys = new HashSet<>();
        for (JobDefinition definition : stateStore.listJobs()) {
            Optional<JobLease> lease = stateStore.getLease(definition.jobId());
            if (lease.isEmpty()) {
                continue;
            }
            JobLease holder = lease.get();
            String key = leaseKey(definition.jobId(), holder.leaseOwnerSite(), holder.leaseOwnerInstance());
            activeLeaseKeys.add(key);
            AtomicInteger value = leaseHolderValues.computeIfAbsent(
                key,
                ignored -> registerLeaseHolderGauge(definition.jobId(), holder.leaseOwnerSite(), holder.leaseOwnerInstance())
            );
            value.set(1);
        }
        removeStaleLeaseSeries(activeLeaseKeys);
    }

    private AtomicLong registerJobLagGauge(String jobId, JobType jobType) {
        AtomicLong holder = new AtomicLong(0L);
        Gauge gauge = Gauge.builder("streammux.job.input_lag", holder, AtomicLong::get)
            .description("Maximum Kafka consumer lag for the job runner")
            .tags("job_id", jobId, "job_type", jobType.name())
            .register(registry);
        jobLagGauges.put(jobId, gauge);
        return holder;
    }

    private AtomicLong registerJobInputRateGauge(String jobId, JobType jobType) {
        AtomicLong holder = new AtomicLong(0L);
        Gauge gauge = Gauge.builder("streammux.job.input_rate", holder, AtomicLong::get)
            .description("Observed input consumption rate for the job runner")
            .tags("job_id", jobId, "job_type", jobType.name())
            .register(registry);
        jobInputRateGauges.put(jobId, gauge);
        return holder;
    }

    private AtomicLong registerJobInputCountGauge(String jobId, JobType jobType) {
        AtomicLong holder = new AtomicLong(0L);
        Gauge gauge = Gauge.builder("streammux.job.input_count", holder, AtomicLong::get)
            .description("Total input records consumed since the job runner started")
            .tags("job_id", jobId, "job_type", jobType.name())
            .register(registry);
        jobInputCountGauges.put(jobId, gauge);
        return holder;
    }

    private AtomicLong registerJobOutputRateGauge(String jobId, JobType jobType) {
        AtomicLong holder = new AtomicLong(0L);
        Gauge gauge = Gauge.builder("streammux.job.output_rate", holder, AtomicLong::get)
            .description("Observed output rate for the job runner")
            .tags("job_id", jobId, "job_type", jobType.name())
            .register(registry);
        jobRateGauges.put(jobId, gauge);
        return holder;
    }

    private AtomicLong registerJobOutputCountGauge(String jobId, JobType jobType) {
        AtomicLong holder = new AtomicLong(0L);
        Gauge gauge = Gauge.builder("streammux.job.output_count", holder, AtomicLong::get)
            .description("Total output records produced since the job runner started")
            .tags("job_id", jobId, "job_type", jobType.name())
            .register(registry);
        jobOutputCountGauges.put(jobId, gauge);
        return holder;
    }

    private AtomicInteger registerLeaseHolderGauge(String jobId, String siteId, String instanceId) {
        AtomicInteger holder = new AtomicInteger(0);
        Gauge gauge = Gauge.builder("streammux.job.lease_holder", holder, AtomicInteger::get)
            .description("1 when the labeled site/instance holds the job lease")
            .tags("job_id", jobId, "site_id", siteId, "instance_id", instanceId)
            .register(registry);
        leaseHolderGauges.put(leaseKey(jobId, siteId, instanceId), gauge);
        return holder;
    }

    private void syncRollupGauges(
        Map<String, Integer> rollup,
        Map<String, AtomicInteger> values,
        Map<String, Gauge> gauges,
        String metricName,
        String description,
        TagBuilder tagBuilder
    ) {
        Set<String> activeKeys = rollup.keySet();
        values.keySet().removeIf(key -> {
            if (activeKeys.contains(key)) {
                return false;
            }
            Gauge gauge = gauges.remove(key);
            if (gauge != null) {
                registry.remove(gauge);
            }
            return true;
        });

        for (Map.Entry<String, Integer> entry : rollup.entrySet()) {
            AtomicInteger holder = values.computeIfAbsent(entry.getKey(), key -> {
                AtomicInteger newHolder = new AtomicInteger(0);
                Tags tags = tagBuilder.build(key);
                Gauge gauge = Gauge.builder(metricName, newHolder, AtomicInteger::get)
                    .description(description)
                    .tags(tags)
                    .register(registry);
                gauges.put(key, gauge);
                return newHolder;
            });
            holder.set(entry.getValue());
        }
    }

    private void removeStaleJobSeries(Set<String> activeJobIds) {
        jobInputLag.keySet().removeIf(jobId -> {
            if (activeJobIds.contains(jobId)) {
                return false;
            }
            removeGauge(jobLagGauges.remove(jobId));
            jobInputRate.remove(jobId);
            removeGauge(jobInputRateGauges.remove(jobId));
            jobInputCount.remove(jobId);
            removeGauge(jobInputCountGauges.remove(jobId));
            jobOutputRate.remove(jobId);
            removeGauge(jobRateGauges.remove(jobId));
            jobOutputCount.remove(jobId);
            removeGauge(jobOutputCountGauges.remove(jobId));
            return true;
        });
    }

    private void removeStaleLeaseSeries(Set<String> activeLeaseKeys) {
        leaseHolderValues.keySet().removeIf(key -> {
            if (activeLeaseKeys.contains(key)) {
                return false;
            }
            removeGauge(leaseHolderGauges.remove(key));
            return true;
        });
    }

    private void removeGauge(Gauge gauge) {
        if (gauge != null) {
            registry.remove(gauge);
        }
    }

    private static String configuredKey(DesiredJobState desiredState, JobType jobType) {
        return desiredState.name() + "|" + jobType.name();
    }

    private static String runtimeKey(RuntimeState state, HealthState health) {
        return state.name() + "|" + health.name();
    }

    private static String leaseKey(String jobId, String siteId, String instanceId) {
        return jobId + "|" + siteId + "|" + instanceId;
    }

    private Tags configuredTags(String key) {
        String[] parts = key.split("\\|", 2);
        return Tags.of("desired_state", parts[0], "job_type", parts[1]);
    }

    private Tags runtimeTags(String key) {
        String[] parts = key.split("\\|", 2);
        return Tags.of("state", parts[0], "health", parts[1]);
    }

    @FunctionalInterface
    private interface TagBuilder {
        Tags build(String key);
    }
}
