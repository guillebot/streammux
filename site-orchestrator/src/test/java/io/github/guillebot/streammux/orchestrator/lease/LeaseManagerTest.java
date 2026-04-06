package io.github.guillebot.streammux.orchestrator.lease;

import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.LeaseStatus;
import io.github.guillebot.streammux.orchestrator.config.SiteIdentityProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaseManagerTest {

    private final LeaseManager leaseManager = new LeaseManager(new SiteIdentityProperties("site-a", "instance-a"));

    @Test
    void claimsActiveJobsWithoutExistingLease() {
        LeaseDecision decision = leaseManager.decide(jobDefinition(DesiredJobState.ACTIVE, 10, 30), null, Instant.parse("2024-01-01T00:00:00Z"));

        assertEquals(LeaseDecision.CLAIM, decision);
    }

    @Test
    void keepsRunningWhenOwnedLeaseIsNotNearHeartbeatWindow() {
        JobLease lease = new JobLease("job-1", 1, "site-a", "instance-a", 2, LeaseStatus.RUNNING, Instant.parse("2024-01-01T00:00:30Z"), Instant.parse("2024-01-01T00:00:00Z"));

        LeaseDecision decision = leaseManager.decide(jobDefinition(DesiredJobState.ACTIVE, 10, 30), lease, Instant.parse("2024-01-01T00:00:15Z"));

        assertEquals(LeaseDecision.KEEP_RUNNING, decision);
    }

    @Test
    void renewsWhenOwnedLeaseEntersHeartbeatWindow() {
        JobLease lease = new JobLease("job-1", 1, "site-a", "instance-a", 2, LeaseStatus.RUNNING, Instant.parse("2024-01-01T00:00:30Z"), Instant.parse("2024-01-01T00:00:00Z"));

        LeaseDecision decision = leaseManager.decide(jobDefinition(DesiredJobState.ACTIVE, 10, 30), lease, Instant.parse("2024-01-01T00:00:25Z"));

        assertEquals(LeaseDecision.RENEW, decision);
    }

    @Test
    void releasesInactiveJobsWhenThisInstanceOwnsTheLease() {
        JobLease lease = new JobLease("job-1", 1, "site-a", "instance-a", 2, LeaseStatus.RUNNING, Instant.parse("2024-01-01T00:00:30Z"), Instant.parse("2024-01-01T00:00:00Z"));

        LeaseDecision decision = leaseManager.decide(jobDefinition(DesiredJobState.PAUSED, 10, 30), lease, Instant.parse("2024-01-01T00:00:10Z"));

        assertEquals(LeaseDecision.RELEASE, decision);
    }

    @Test
    void ignoresForeignLeaseUntilItExpires() {
        JobLease lease = new JobLease("job-1", 1, "site-b", "instance-b", 2, LeaseStatus.RUNNING, Instant.parse("2024-01-01T00:00:30Z"), Instant.parse("2024-01-01T00:00:00Z"));

        LeaseDecision decision = leaseManager.decide(jobDefinition(DesiredJobState.ACTIVE, 10, 30), lease, Instant.parse("2024-01-01T00:00:15Z"));

        assertEquals(LeaseDecision.IGNORE, decision);
    }

    @Test
    void claimCreatesClaimedLeaseWithIncrementedEpoch() {
        JobLease currentLease = new JobLease("job-1", 1, "site-b", "instance-b", 4, LeaseStatus.RUNNING, Instant.parse("2024-01-01T00:00:30Z"), Instant.parse("2024-01-01T00:00:00Z"));
        Instant now = Instant.parse("2024-01-01T00:01:00Z");

        JobLease claimed = leaseManager.claim(jobDefinition(DesiredJobState.ACTIVE, 5, 20), currentLease, now);

        assertEquals("site-a", claimed.leaseOwnerSite());
        assertEquals("instance-a", claimed.leaseOwnerInstance());
        assertEquals(5, claimed.leaseEpoch());
        assertEquals(LeaseStatus.CLAIMED, claimed.status());
        assertEquals(now.plusSeconds(20), claimed.leaseExpiresAt());
    }

    @Test
    void renewKeepsOwnershipAndMarksLeaseRunning() {
        JobLease currentLease = new JobLease("job-1", 1, "site-a", "instance-a", 4, LeaseStatus.CLAIMED, Instant.parse("2024-01-01T00:00:30Z"), Instant.parse("2024-01-01T00:00:00Z"));
        Instant now = Instant.parse("2024-01-01T00:01:00Z");

        JobLease renewed = leaseManager.renew(jobDefinition(DesiredJobState.ACTIVE, 5, 20), currentLease, now);

        assertEquals(4, renewed.leaseEpoch());
        assertEquals(LeaseStatus.RUNNING, renewed.status());
        assertEquals(now.plusSeconds(20), renewed.leaseExpiresAt());
    }

    @Test
    void ownsLeaseChecksSiteAndInstanceIdentity() {
        assertTrue(leaseManager.ownsLease(new JobLease("job-1", 1, "site-a", "instance-a", 1, LeaseStatus.RUNNING, Instant.now(), Instant.now())));
        assertFalse(leaseManager.ownsLease(new JobLease("job-1", 1, "site-a", "instance-b", 1, LeaseStatus.RUNNING, Instant.now(), Instant.now())));
    }

    private static JobDefinition jobDefinition(DesiredJobState desiredState, long heartbeatIntervalSeconds, long leaseDurationSeconds) {
        return new JobDefinition(
            "job-1",
            1,
            JobType.ROUTE_APP,
            desiredState,
            1,
            "site-a",
            new LeasePolicy(heartbeatIntervalSeconds, leaseDurationSeconds, 1000, true),
            1,
            null,
            Map.of(),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }
}
