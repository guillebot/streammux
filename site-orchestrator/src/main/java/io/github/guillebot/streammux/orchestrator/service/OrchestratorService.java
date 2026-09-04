package io.github.guillebot.streammux.orchestrator.service;

import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.RuntimeState;
import io.github.guillebot.streammux.contracts.spi.JobRunner;
import io.github.guillebot.streammux.orchestrator.config.OrchestratorProperties;
import io.github.guillebot.streammux.orchestrator.config.SiteIdentityProperties;
import io.github.guillebot.streammux.orchestrator.lease.LeaseDecision;
import io.github.guillebot.streammux.orchestrator.lease.LeaseManager;
import io.github.guillebot.streammux.orchestrator.metrics.StreammuxOrchestratorMetrics;
import io.github.guillebot.streammux.orchestrator.runner.JobRunnerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrchestratorService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrchestratorService.class);

    private final LeaseManager leaseManager;
    private final SiteIdentityProperties siteIdentity;
    private final JobRunnerRegistry jobRunnerRegistry;
    private final OrchestratorEventPublisher eventPublisher;
    private final OrchestratorProperties orchestratorProperties;
    private final StreammuxOrchestratorMetrics orchestratorMetrics;
    private final Map<String, Long> maxObservedEpochs = new ConcurrentHashMap<>();
    private final Map<String, Long> activeLeaseEpochs = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingRunnerEpochs = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastClaimAttempts = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastLeaseLossAt = new ConcurrentHashMap<>();
    private final Map<String, Instant> failedSince = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastRestartAttempts = new ConcurrentHashMap<>();
    private final Instant startedAt = Instant.now();

    public OrchestratorService(
        LeaseManager leaseManager,
        SiteIdentityProperties siteIdentity,
        JobRunnerRegistry jobRunnerRegistry,
        OrchestratorEventPublisher eventPublisher,
        OrchestratorProperties orchestratorProperties,
        StreammuxOrchestratorMetrics orchestratorMetrics
    ) {
        this.leaseManager = leaseManager;
        this.siteIdentity = siteIdentity;
        this.jobRunnerRegistry = jobRunnerRegistry;
        this.eventPublisher = eventPublisher;
        this.orchestratorProperties = orchestratorProperties;
        this.orchestratorMetrics = orchestratorMetrics;
    }

    public Set<String> activeJobIds() {
        return Set.copyOf(activeLeaseEpochs.keySet());
    }

    public void observeLease(JobLease lease) {
        if (lease == null) {
            return;
        }
        maxObservedEpochs.merge(lease.jobId(), lease.leaseEpoch(), Math::max);
    }

    public boolean shouldPublishLease(JobLease lease) {
        if (lease == null) {
            return false;
        }
        long maxObserved = maxObservedEpochs.getOrDefault(lease.jobId(), 0L);
        return lease.leaseEpoch() >= maxObserved;
    }

    public JobLease reconcile(JobDefinition definition, JobLease currentLease) {
        stopIfLeaseLost(definition, currentLease);
        Instant now = Instant.now();
        LeaseDecision decision = leaseManager.decide(definition, currentLease, now);
        return switch (decision) {
            case CLAIM -> claimBackoffElapsed(definition, now) ? claim(definition, currentLease, now) : currentLease;
            case RENEW -> renew(definition, currentLease, now);
            case RELEASE -> release(definition);
            case KEEP_RUNNING, IGNORE -> currentLease;
        };
    }

    public JobRuntimeStatus status(String jobId, JobDefinition definition) {
        return jobRunnerRegistry.resolve(definition).status(jobId);
    }

    public void recoverOwnedRunnerIfMissing(JobDefinition definition, JobLease lease) {
        if (definition.desiredState() != DesiredJobState.ACTIVE) return;
        if (lease == null || !leaseManager.ownsLease(lease) || lease.isExpired(Instant.now())) return;
        String jobId = definition.jobId();
        Long activeEpoch = activeLeaseEpochs.get(jobId);
        if (activeEpoch != null && activeEpoch == lease.leaseEpoch()) return;
        if (pendingRunnerEpochs.containsKey(jobId)) return;
        startRunner(definition, lease.leaseEpoch(), "Runner recovered after restart", Map.of("recovery", true, "leaseEpoch", lease.leaseEpoch()));
    }

    /**
     * Starts a runner after Kafka confirms this instance holds the pending claim epoch.
     * Invoked from the job-leases listener path only so multiple simultaneous claimants
     * do not all start Kafka Streams before the compacted lease record settles.
     */
    public void maybeStartConfirmedRunner(JobDefinition definition, JobLease lease) {
        if (definition.desiredState() != DesiredJobState.ACTIVE || lease == null || !leaseManager.ownsLease(lease)) {
            return;
        }

        String jobId = definition.jobId();
        Long pendingEpoch = pendingRunnerEpochs.get(jobId);
        if (pendingEpoch == null || pendingEpoch != lease.leaseEpoch()) {
            return;
        }

        Long activeEpoch = activeLeaseEpochs.get(jobId);
        if (activeEpoch != null && activeEpoch == lease.leaseEpoch()) {
            pendingRunnerEpochs.remove(jobId);
            return;
        }

        pendingRunnerEpochs.remove(jobId);
        startRunner(definition, lease.leaseEpoch(), "Runner started", Map.of("leaseEpoch", lease.leaseEpoch()));
    }

    /**
     * Restarts a failed runner when this instance still holds the lease. Called from the reconcile
     * loop after lease renewal so transient Kafka/network failures can recover without manual intervention.
     */
    public void maybeRestartFailedRunner(JobDefinition definition, JobLease lease) {
        long restartDelayMs = orchestratorProperties.runnerRestartDelayMs();
        if (restartDelayMs <= 0) {
            return;
        }
        if (definition.desiredState() != DesiredJobState.ACTIVE) {
            return;
        }
        if (lease == null || !leaseManager.ownsLease(lease)) {
            return;
        }

        String jobId = definition.jobId();
        Long activeEpoch = activeLeaseEpochs.get(jobId);
        if (activeEpoch == null || activeEpoch != lease.leaseEpoch()) {
            return;
        }

        JobRuntimeStatus status = status(jobId, definition);
        if (status == null) {
            return;
        }
        if (!needsRestart(status.state())) {
            failedSince.remove(jobId);
            return;
        }

        Instant now = Instant.now();
        Instant observedFailureAt = failedSince.computeIfAbsent(jobId, ignored -> now);
        if (now.isBefore(observedFailureAt.plusMillis(restartDelayMs))) {
            return;
        }

        Instant lastAttempt = lastRestartAttempts.get(jobId);
        if (lastAttempt != null && now.isBefore(lastAttempt.plusMillis(restartDelayMs))) {
            return;
        }

        restartRunner(definition, lease, now);
    }

    private static boolean needsRestart(RuntimeState state) {
        return state == RuntimeState.FAILED || state == RuntimeState.STOPPED;
    }

    private boolean claimBackoffElapsed(JobDefinition definition, Instant now) {
        String jobId = definition.jobId();
        Instant lastLoss = lastLeaseLossAt.get(jobId);
        long backoffMillis = Math.max(0L, definition.leasePolicy().claimBackoffMillis());
        if (lastLoss != null && now.isBefore(lastLoss.plusMillis(backoffMillis))) {
            return false;
        }

        Instant lastAttempt = lastClaimAttempts.get(jobId);
        if (lastAttempt == null) {
            long staggerMillis = backoffMillis == 0L
                ? 0L
                : Math.floorMod(siteIdentity.instanceId().hashCode(), backoffMillis);
            return !now.isBefore(startedAt.plusMillis(staggerMillis));
        }
        return !now.isBefore(lastAttempt.plusMillis(backoffMillis));
    }

    private JobLease claim(JobDefinition definition, JobLease currentLease, Instant now) {
        long maxObserved = maxObservedEpochs.getOrDefault(definition.jobId(), 0L);
        JobLease newLease = leaseManager.claim(definition, currentLease, maxObserved, now);
        lastClaimAttempts.put(definition.jobId(), now);
        pendingRunnerEpochs.put(definition.jobId(), newLease.leaseEpoch());
        observeLease(newLease);
        eventPublisher.publishForDefinition(
            definition,
            EventType.CLAIMED,
            "Lease claimed",
            Map.of("leaseEpoch", newLease.leaseEpoch())
        );
        LOGGER.info("Published claim for job {} at epoch {}", definition.jobId(), newLease.leaseEpoch());
        return newLease;
    }

    private void startRunner(JobDefinition definition, long leaseEpoch, String message, Map<String, Object> attributes) {
        JobRunner runner = jobRunnerRegistry.resolve(definition);
        try {
            runner.start(definition, leaseEpoch);
            activeLeaseEpochs.put(definition.jobId(), leaseEpoch);
            eventPublisher.publishForDefinition(definition, EventType.STARTED, message, attributes);
            LOGGER.info("Started job {} at epoch {}", definition.jobId(), leaseEpoch);
        } catch (RuntimeException ex) {
            activeLeaseEpochs.remove(definition.jobId());
            eventPublisher.publishForDefinition(
                definition,
                EventType.FAILED,
                ex.getMessage() != null ? ex.getMessage() : "Runner start failed",
                Map.of("leaseEpoch", leaseEpoch, "error", ex.getClass().getSimpleName())
            );
            LOGGER.warn("Runner start failed for job {} at epoch {}: {}", definition.jobId(), leaseEpoch, ex.getMessage());
            LOGGER.error("Failed to start job {} at epoch {}", definition.jobId(), leaseEpoch, ex);
            orchestratorMetrics.recordRunnerStartFailure(definition.jobId(), definition.jobType());
            throw ex;
        }
    }

    private void restartRunner(JobDefinition definition, JobLease lease, Instant now) {
        String jobId = definition.jobId();
        lastRestartAttempts.put(jobId, now);

        JobRunner runner = jobRunnerRegistry.resolve(definition);
        runner.stop(jobId);
        try {
            runner.start(definition, lease.leaseEpoch());
            activeLeaseEpochs.put(jobId, lease.leaseEpoch());
            failedSince.remove(jobId);
            lastRestartAttempts.remove(jobId);
            eventPublisher.publishForDefinition(
                definition,
                EventType.STARTED,
                "Runner restarted after failure",
                Map.of("leaseEpoch", lease.leaseEpoch(), "restart", true)
            );
            LOGGER.info("Restarted job {} at epoch {} after runner failure", jobId, lease.leaseEpoch());
        } catch (RuntimeException ex) {
            eventPublisher.publishForDefinition(
                definition,
                EventType.FAILED,
                ex.getMessage() != null ? ex.getMessage() : "Runner restart failed",
                Map.of("leaseEpoch", lease.leaseEpoch(), "error", ex.getClass().getSimpleName(), "restart", true)
            );
            LOGGER.error("Failed to restart job {} at epoch {}", jobId, lease.leaseEpoch(), ex);
            orchestratorMetrics.recordRunnerStartFailure(jobId, definition.jobType());
        }
    }

    private JobLease renew(JobDefinition definition, JobLease currentLease, Instant now) {
        JobLease renewed = leaseManager.renew(definition, currentLease, now);
        if (!shouldPublishLease(renewed)) {
            LOGGER.warn(
                "Skipping stale lease renew for {} at epoch {} (max observed {})",
                definition.jobId(),
                renewed.leaseEpoch(),
                maxObservedEpochs.getOrDefault(definition.jobId(), 0L)
            );
            return currentLease;
        }
        activeLeaseEpochs.put(definition.jobId(), renewed.leaseEpoch());
        observeLease(renewed);
        LOGGER.debug("Renewed lease for {} at epoch {}", definition.jobId(), renewed.leaseEpoch());
        return renewed;
    }

    private void stopIfLeaseLost(JobDefinition definition, JobLease currentLease) {
        Long activeLeaseEpoch = activeLeaseEpochs.get(definition.jobId());
        if (activeLeaseEpoch == null) {
            return;
        }

        boolean stillOwnsLease = currentLease != null
            && leaseManager.ownsLease(currentLease)
            && currentLease.leaseEpoch() == activeLeaseEpoch;

        if (!stillOwnsLease) {
            JobRunner runner = jobRunnerRegistry.resolve(definition);
            runner.stop(definition.jobId());
            activeLeaseEpochs.remove(definition.jobId());
            pendingRunnerEpochs.remove(definition.jobId());
            lastRestartAttempts.remove(definition.jobId());
            failedSince.remove(definition.jobId());
            lastLeaseLossAt.put(definition.jobId(), Instant.now());
            String owner = currentLease == null
                ? "unknown"
                : currentLease.leaseOwnerSite() + "/" + currentLease.leaseOwnerInstance();
            eventPublisher.publishForDefinition(
                definition,
                EventType.RELEASED,
                "Stopped after losing lease to " + owner,
                Map.of("leaseEpoch", activeLeaseEpoch, "newOwner", owner)
            );
            LOGGER.info("Stopped job {} after losing lease ownership", definition.jobId());
        }
    }

    private JobLease release(JobDefinition definition) {
        JobRunner runner = jobRunnerRegistry.resolve(definition);
        runner.stop(definition.jobId());
        activeLeaseEpochs.remove(definition.jobId());
        pendingRunnerEpochs.remove(definition.jobId());
        lastRestartAttempts.remove(definition.jobId());
        failedSince.remove(definition.jobId());
        lastClaimAttempts.remove(definition.jobId());
        lastLeaseLossAt.remove(definition.jobId());

        if (definition.desiredState() == DesiredJobState.PAUSED) {
            eventPublisher.publishForDefinition(
                definition,
                EventType.STOPPED,
                "Runner stopped (desiredState=PAUSED)",
                Map.of("desiredState", definition.desiredState().name())
            );
        } else {
            eventPublisher.publishForDefinition(
                definition,
                EventType.RELEASED,
                "Runner released",
                Map.of("desiredState", definition.desiredState().name())
            );
        }
        LOGGER.info("Released job {}", definition.jobId());
        return null;
    }
}
