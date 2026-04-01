package io.github.guillebot.streammux.orchestrator.lease;

import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.LeaseStatus;
import io.github.guillebot.streammux.orchestrator.config.SiteIdentityProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class LeaseManager {
    private final SiteIdentityProperties siteIdentity;

    public LeaseManager(SiteIdentityProperties siteIdentity) { this.siteIdentity = siteIdentity; }

    public LeaseDecision decide(JobDefinition definition, JobLease currentLease, Instant now) {
        if (definition.desiredState() != DesiredJobState.ACTIVE) return ownsLease(currentLease) ? LeaseDecision.RELEASE : LeaseDecision.IGNORE;
        if (currentLease == null || currentLease.isExpired(now)) return LeaseDecision.CLAIM;
        if (ownsLease(currentLease)) return currentLease.leaseExpiresAt().minusSeconds(definition.leasePolicy().heartbeatIntervalSeconds()).isBefore(now) ? LeaseDecision.RENEW : LeaseDecision.KEEP_RUNNING;
        return LeaseDecision.IGNORE;
    }

    public JobLease claim(JobDefinition definition, JobLease currentLease, Instant now) {
        long nextEpoch = currentLease == null ? 1 : currentLease.leaseEpoch() + 1;
        return new JobLease(definition.jobId(), definition.jobVersion(), siteIdentity.siteId(), siteIdentity.instanceId(), nextEpoch, LeaseStatus.CLAIMED, now.plus(definition.leasePolicy().leaseDurationSeconds(), ChronoUnit.SECONDS), now);
    }

    public JobLease renew(JobDefinition definition, JobLease currentLease, Instant now) {
        return new JobLease(currentLease.jobId(), definition.jobVersion(), currentLease.leaseOwnerSite(), currentLease.leaseOwnerInstance(), currentLease.leaseEpoch(), LeaseStatus.RUNNING, now.plus(definition.leasePolicy().leaseDurationSeconds(), ChronoUnit.SECONDS), now);
    }

    public boolean ownsLease(JobLease lease) {
        return lease != null && siteIdentity.siteId().equals(lease.leaseOwnerSite()) && siteIdentity.instanceId().equals(lease.leaseOwnerInstance());
    }
}
