package io.github.guillebot.streammux.contracts.model;

public record LeasePolicy(long heartbeatIntervalSeconds, long leaseDurationSeconds, long claimBackoffMillis, boolean allowFailover) {
    public static LeasePolicy defaults() {
        return new LeasePolicy(10, 30, 5000, true);
    }
}
