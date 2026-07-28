package io.github.guillebot.streammux.orchestrator.service;

import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.RuntimeState;
import io.github.guillebot.streammux.contracts.spi.JobRunner;
import io.github.guillebot.streammux.orchestrator.config.OrchestratorProperties;
import io.github.guillebot.streammux.orchestrator.lease.LeaseDecision;
import io.github.guillebot.streammux.orchestrator.lease.LeaseManager;
import io.github.guillebot.streammux.orchestrator.runner.JobRunnerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrchestratorService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrchestratorService.class);

    private final LeaseManager leaseManager;
    private final JobRunnerRegistry jobRunnerRegistry;
    private final OrchestratorEventPublisher eventPublisher;
    private final OrchestratorProperties orchestratorProperties;
    private final Map<String, Long> activeLeaseEpochs = new ConcurrentHashMap<>();
    private final Map<String, Instant> failedSince = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastRestartAttempts = new ConcurrentHashMap<>();

    public OrchestratorService(
        LeaseManager leaseManager,
        JobRunnerRegistry jobRunnerRegistry,
        OrchestratorEventPublisher eventPublisher,
        OrchestratorProperties orchestratorProperties
    ) {
        this.leaseManager = leaseManager;
        this.jobRunnerRegistry = jobRunnerRegistry;
        this.eventPublisher = eventPublisher;
        this.orchestratorProperties = orchestratorProperties;
    }

    public JobLease reconcile(JobDefinition definition, JobLease currentLease) {
        stopIfLeaseLost(definition, currentLease);
        Instant now = Instant.now();
        LeaseDecision decision = leaseManager.decide(definition, currentLease, now);
        return switch (decision) {
            case CLAIM -> claim(definition, currentLease, now);
            case RENEW -> renew(definition, currentLease, now);
            case RELEASE -> release(definition);
            case KEEP_RUNNING, IGNORE -> currentLease;
        };
    }

    public JobRuntimeStatus status(String jobId, JobDefinition definition) {
        return jobRunnerRegistry.resolve(definition).status(jobId);
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

    private JobLease claim(JobDefinition definition, JobLease currentLease, Instant now) {
        JobLease newLease = leaseManager.claim(definition, currentLease, now);
        eventPublisher.publishForDefinition(
            definition,
            EventType.CLAIMED,
            "Lease claimed",
            Map.of("leaseEpoch", newLease.leaseEpoch())
        );

        JobRunner runner = jobRunnerRegistry.resolve(definition);
        try {
            runner.start(definition, newLease.leaseEpoch());
            activeLeaseEpochs.put(definition.jobId(), newLease.leaseEpoch());
            eventPublisher.publishForDefinition(
                definition,
                EventType.STARTED,
                "Runner started",
                Map.of("leaseEpoch", newLease.leaseEpoch())
            );
            LOGGER.info("Claimed job {} with epoch {}", definition.jobId(), newLease.leaseEpoch());
        } catch (RuntimeException ex) {
            activeLeaseEpochs.remove(definition.jobId());
            eventPublisher.publishForDefinition(
                definition,
                EventType.FAILED,
                ex.getMessage() != null ? ex.getMessage() : "Runner start failed",
                Map.of("leaseEpoch", newLease.leaseEpoch(), "error", ex.getClass().getSimpleName())
            );
            LOGGER.error("Failed to start job {} at epoch {}", definition.jobId(), newLease.leaseEpoch(), ex);
            throw ex;
        }
        return newLease;
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
        }
    }

    private JobLease renew(JobDefinition definition, JobLease currentLease, Instant now) {
        JobLease renewed = leaseManager.renew(definition, currentLease, now);
        activeLeaseEpochs.put(definition.jobId(), renewed.leaseEpoch());
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
            lastRestartAttempts.remove(definition.jobId());
            failedSince.remove(definition.jobId());
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
        lastRestartAttempts.remove(definition.jobId());
        failedSince.remove(definition.jobId());

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
