package io.github.guillebot.streammux.contracts.kafka;

/**
 * Canonical Kafka Streams {@code application.id} values for Streammux job runners.
 * Kafka exposes these as consumer group IDs in cluster lag dashboards.
 */
public final class KafkaStreamsApplicationIds {

    public static final String PREFIX = "streammux-";

    private KafkaStreamsApplicationIds() {}

    public static String applicationId(String jobId, long leaseEpoch) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId is required");
        }
        return PREFIX + jobId + "-" + leaseEpoch;
    }
}
