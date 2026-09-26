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
        if (definition.desiredState() != DesiredJobState.ACTIVE) {
            return ownsLease(currentLease) ? LeaseDecision.RELEASE : LeaseDecision.IGNORE;
        }
        if (ownsLease(currentLease)) {
            return shouldHeartbeat(definition, currentLease, now) ? LeaseDecision.RENEW : LeaseDecision.KEEP_RUNNING;
        }
        if (currentLease == null || currentLease.isExpired(now)) {
            return LeaseDecision.CLAIM;
        }
        return LeaseDecision.IGNORE;
    }

    private static boolean shouldHeartbeat(JobDefinition definition, JobLease currentLease, Instant now) {
        if (currentLease.isExpired(now)) {
            return true;
        }
        long heartbeatSeconds = Math.max(1L, definition.leasePolicy().heartbeatIntervalSeconds());
        return currentLease.leaseExpiresAt().minusSeconds(heartbeatSeconds).isBefore(now);
    }

    public JobLease claim(JobDefinition definition, JobLease currentLease, long maxObservedEpoch, Instant now) {
        long baseEpoch = Math.max(currentLease == null ? 0L : currentLease.leaseEpoch(), maxObservedEpoch);
        long nextEpoch = baseEpoch + 1;
        return new JobLease(definition.jobId(), definition.jobVersion(), siteIdentity.siteId(), siteIdentity.instanceId(), nextEpoch, LeaseStatus.CLAIMED, now.plus(definition.leasePolicy().leaseDurationSeconds(), ChronoUnit.SECONDS), now);
    }

    public JobLease renew(JobDefinition definition, JobLease currentLease, Instant now) {
        return new JobLease(currentLease.jobId(), definition.jobVersion(), currentLease.leaseOwnerSite(), currentLease.leaseOwnerInstance(), currentLease.leaseEpoch(), LeaseStatus.RUNNING, now.plus(definition.leasePolicy().leaseDurationSeconds(), ChronoUnit.SECONDS), now);
    }

    public boolean ownsLease(JobLease lease) {
        return lease != null && siteIdentity.siteId().equals(lease.leaseOwnerSite()) && siteIdentity.instanceId().equals(lease.leaseOwnerInstance());
    }
}
