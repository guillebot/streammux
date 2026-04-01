package io.github.guillebot.streammux.contracts.model;

import java.time.Instant;

public record JobLease(String jobId, long jobVersion, String leaseOwnerSite, String leaseOwnerInstance, long leaseEpoch, LeaseStatus status, Instant leaseExpiresAt, Instant lastHeartbeatAt) {
    public boolean isExpired(Instant now) {
        return leaseExpiresAt != null && leaseExpiresAt.isBefore(now);
    }
}
