package io.github.guillebot.streammux.contracts.kafka;

/**
 * Canonical Kafka Streams {@code application.id} values for Streammux job runners.
 * Kafka exposes these as consumer group IDs in cluster lag dashboards.
 * <p>
 * The id is stable per {@code jobId} so restarts, recoveries, and lease failovers reuse
 * one consumer group instead of abandoning a new group on every lease epoch bump.
 * Current runners are stateless; lease epochs remain an orchestrator concern only.
 */
public final class KafkaStreamsApplicationIds {

    public static final String PREFIX = "streammux-";

    private KafkaStreamsApplicationIds() {}

    public static String applicationId(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId is required");
        }
        return PREFIX + jobId;
    }

    /**
     * Legacy epoch-scoped id ({@code streammux-{jobId}-{leaseEpoch}}) used before stable ids.
     * Useful when cleaning up abandoned consumer groups after upgrading runners.
     */
    public static String legacyApplicationId(String jobId, long leaseEpoch) {
        return applicationId(jobId) + "-" + leaseEpoch;
    }
}
