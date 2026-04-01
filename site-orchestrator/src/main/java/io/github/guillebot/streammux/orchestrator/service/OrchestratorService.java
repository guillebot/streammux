package io.github.guillebot.streammux.orchestrator.service;

import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
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
    private final Map<String, Long> activeLeaseEpochs = new ConcurrentHashMap<>();

    public OrchestratorService(LeaseManager leaseManager, JobRunnerRegistry jobRunnerRegistry) {
        this.leaseManager = leaseManager;
        this.jobRunnerRegistry = jobRunnerRegistry;
    }

    public JobLease reconcile(JobDefinition definition, JobLease currentLease) {
        Instant now = Instant.now();
        LeaseDecision decision = leaseManager.decide(definition, currentLease, now);
        return switch (decision) {
            case CLAIM -> claim(definition, currentLease, now);
            case RENEW -> renew(definition, currentLease, now);
            case RELEASE -> release(definition);
            case KEEP_RUNNING, IGNORE -> currentLease;
        };
    }

    private JobLease claim(JobDefinition definition, JobLease currentLease, Instant now) {
        JobLease newLease = leaseManager.claim(definition, currentLease, now);
        JobRunner runner = jobRunnerRegistry.resolve(definition);
        runner.start(definition, newLease.leaseEpoch());
        activeLeaseEpochs.put(definition.jobId(), newLease.leaseEpoch());
        LOGGER.info("Claimed job {} with epoch {}", definition.jobId(), newLease.leaseEpoch());
        return newLease;
    }

    private JobLease renew(JobDefinition definition, JobLease currentLease, Instant now) {
        JobLease renewed = leaseManager.renew(definition, currentLease, now);
        activeLeaseEpochs.put(definition.jobId(), renewed.leaseEpoch());
        LOGGER.debug("Renewed lease for {} at epoch {}", definition.jobId(), renewed.leaseEpoch());
        return renewed;
    }

    private JobLease release(JobDefinition definition) {
        JobRunner runner = jobRunnerRegistry.resolve(definition);
        runner.stop(definition.jobId());
        activeLeaseEpochs.remove(definition.jobId());
        LOGGER.info("Released job {}", definition.jobId());
        return null;
    }
}
