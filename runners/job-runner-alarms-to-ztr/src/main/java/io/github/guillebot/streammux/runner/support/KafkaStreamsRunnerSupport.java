package io.github.guillebot.streammux.runner.support;

import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.LagMetrics;
import io.github.guillebot.streammux.contracts.model.RuntimeState;
import io.github.guillebot.streammux.contracts.model.WorkerMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.streams.KafkaStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class KafkaStreamsRunnerSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaStreamsRunnerSupport.class);
    private static final String CONSUMER_FETCH_MANAGER_METRICS = "consumer-fetch-manager-metrics";
    private static final String PRODUCER_TOPIC_METRICS = "producer-topic-metrics";
    private static final String STREAM_TOPIC_METRICS = "stream-topic-metrics";
    private static final LagMetrics EMPTY_LAG_METRICS = new LagMetrics(0, 0, 0, 0, 0);

    private final Map<String, KafkaStreams> runningJobs = new ConcurrentHashMap<>();
    private final Map<String, KafkaStreams.State> streamStates = new ConcurrentHashMap<>();
    private final Map<String, String> failureReasons = new ConcurrentHashMap<>();

    public void register(String jobId, KafkaStreams streams) {
        stop(jobId);
        streams.setStateListener((newState, oldState) -> {
            streamStates.put(jobId, newState);
            if (newState == KafkaStreams.State.ERROR || newState == KafkaStreams.State.PENDING_ERROR) {
                failureReasons.put(jobId, "Kafka Streams entered " + newState);
                LOGGER.warn("Job {} streams state {} (was {})", jobId, newState, oldState);
            } else if (newState == KafkaStreams.State.RUNNING) {
                failureReasons.remove(jobId);
            }
        });
        streamStates.put(jobId, streams.state());
        runningJobs.put(jobId, streams);
    }

    public void stop(String jobId) {
        KafkaStreams streams = runningJobs.remove(jobId);
        streamStates.remove(jobId);
        failureReasons.remove(jobId);
        if (streams != null) {
            streams.close();
        }
    }

    public void recordStartFailure(String jobId, Exception ex) {
        failureReasons.put(jobId, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
    }

    public JobRuntimeStatus status(String jobId, String topologyName) {
        KafkaStreams streams = runningJobs.get(jobId);
        if (streams == null) {
            return new JobRuntimeStatus(
                jobId,
                0,
                RuntimeState.STOPPED,
                HealthState.UNKNOWN,
                Instant.now(),
                new WorkerMetadata(jobId, topologyName, RuntimeState.STOPPED.name(), Map.of()),
                failureReasons.get(jobId),
                EMPTY_LAG_METRICS
            );
        }

        KafkaStreams.State kafkaState = streamStates.getOrDefault(jobId, streams.state());
        RuntimeState runtimeState = mapRuntimeState(kafkaState);
        HealthState healthState = mapHealthState(kafkaState);
        String failureReason = failureReasons.get(jobId);
        if (failureReason == null && (kafkaState == KafkaStreams.State.ERROR || kafkaState == KafkaStreams.State.PENDING_ERROR)) {
            failureReason = "Kafka Streams state: " + kafkaState;
        }

        return new JobRuntimeStatus(
            jobId,
            0,
            runtimeState,
            healthState,
            Instant.now(),
            new WorkerMetadata(jobId, topologyName, kafkaState.name(), Map.of("kafkaStreamsState", kafkaState.name())),
            failureReason,
            extractLagMetrics(streams)
        );
    }

    static LagMetrics extractLagMetrics(KafkaStreams streams) {
        return extractLagMetrics(streams.metrics());
    }

    static LagMetrics extractLagMetrics(Map<MetricName, ? extends Metric> metrics) {
        Map<String, Long> consumedTotalByClient = new HashMap<>();
        Map<String, Double> consumedRateByClient = new HashMap<>();
        Map<String, Long> producedTotalBySink = new HashMap<>();
        Set<String> sinkTopics = new HashSet<>();
        Map<String, Double> producedRateByClientTopic = new HashMap<>();
        long maxLag = 0;

        for (Map.Entry<MetricName, ? extends Metric> entry : metrics.entrySet()) {
            MetricName metricName = entry.getKey();
            Object value = entry.getValue().metricValue();
            if (!(value instanceof Number number)) {
                continue;
            }

            if ("records-lag-max".equals(metricName.name())) {
                maxLag = Math.max(maxLag, number.longValue());
                continue;
            }

            if (CONSUMER_FETCH_MANAGER_METRICS.equals(metricName.group())) {
                Map<String, String> tags = metricName.tags();
                if (tags.containsKey("topic") || tags.containsKey("partition")) {
                    continue;
                }

                String clientId = tags.getOrDefault("client-id", "");
                switch (metricName.name()) {
                    case "records-consumed-total" ->
                        consumedTotalByClient.merge(clientId, number.longValue(), Math::max);
                    case "records-consumed-rate" ->
                        consumedRateByClient.merge(clientId, number.doubleValue(), Math::max);
                    default -> { }
                }
                continue;
            }

            if (!STREAM_TOPIC_METRICS.equals(metricName.group())) {
                continue;
            }

            Map<String, String> tags = metricName.tags();
            if (!tags.containsKey("topic") || tags.containsKey("partition")) {
                continue;
            }

            String topic = tags.get("topic");
            String sinkKey = tags.getOrDefault("processor-node-id", "") + "|" + topic;
            if ("records-produced-total".equals(metricName.name())) {
                producedTotalBySink.merge(sinkKey, number.longValue(), Math::max);
                sinkTopics.add(topic);
            }
            continue;
        }

        for (Map.Entry<MetricName, ? extends Metric> entry : metrics.entrySet()) {
            MetricName metricName = entry.getKey();
            if (!PRODUCER_TOPIC_METRICS.equals(metricName.group())
                || !"record-send-rate".equals(metricName.name())) {
                continue;
            }

            Object value = entry.getValue().metricValue();
            if (!(value instanceof Number number)) {
                continue;
            }

            Map<String, String> tags = metricName.tags();
            String topic = tags.get("topic");
            if (topic == null || !sinkTopics.contains(topic)) {
                continue;
            }

            String rateKey = tags.getOrDefault("client-id", "") + "|" + topic;
            producedRateByClientTopic.merge(rateKey, number.doubleValue(), Math::max);
        }

        long inputCount = consumedTotalByClient.values().stream().mapToLong(Long::longValue).sum();
        double inputRate = consumedRateByClient.values().stream().mapToDouble(Double::doubleValue).sum();
        long outputCount = producedTotalBySink.values().stream().mapToLong(Long::longValue).sum();
        double outputRate = producedRateByClientTopic.values().stream().mapToDouble(Double::doubleValue).sum();
        return new LagMetrics(
            maxLag,
            Math.round(inputRate),
            inputCount,
            Math.round(outputRate),
            outputCount
        );
    }

    private static RuntimeState mapRuntimeState(KafkaStreams.State kafkaState) {
        return switch (kafkaState) {
            case RUNNING, REBALANCING -> RuntimeState.RUNNING;
            case ERROR, PENDING_ERROR -> RuntimeState.FAILED;
            case PENDING_SHUTDOWN, NOT_RUNNING -> RuntimeState.STOPPED;
            default -> RuntimeState.STARTING;
        };
    }

    private static HealthState mapHealthState(KafkaStreams.State kafkaState) {
        return switch (kafkaState) {
            case RUNNING -> HealthState.HEALTHY;
            case REBALANCING -> HealthState.DEGRADED;
            case ERROR, PENDING_ERROR -> HealthState.UNHEALTHY;
            default -> HealthState.UNKNOWN;
        };
    }
}
