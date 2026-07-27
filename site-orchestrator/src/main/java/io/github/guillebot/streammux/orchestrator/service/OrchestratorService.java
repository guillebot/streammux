package io.github.guillebot.streammux.orchestrator.service;

import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.spi.JobRunner;
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
    private final Map<String, Long> activeLeaseEpochs = new ConcurrentHashMap<>();

    public OrchestratorService(
        LeaseManager leaseManager,
        JobRunnerRegistry jobRunnerRegistry,
        OrchestratorEventPublisher eventPublisher
    ) {
        this.leaseManager = leaseManager;
        this.jobRunnerRegistry = jobRunnerRegistry;
        this.eventPublisher = eventPublisher;
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
