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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class KafkaStreamsRunnerSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaStreamsRunnerSupport.class);

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
                new LagMetrics(0, 0, 0)
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
        long processedCount = 0;
        double processRate = 0;
        long maxLag = 0;

        for (Map.Entry<MetricName, ? extends Metric> entry : metrics.entrySet()) {
            MetricName metricName = entry.getKey();
            Object value = entry.getValue().metricValue();
            if (!(value instanceof Number number)) {
                continue;
            }

            switch (metricName.name()) {
                case "records-consumed-total" -> processedCount += number.longValue();
                case "records-consumed-rate" -> processRate += number.doubleValue();
                case "records-lag-max" -> maxLag = Math.max(maxLag, number.longValue());
                default -> { }
            }
        }

        return new LagMetrics(maxLag, Math.round(processRate), processedCount);
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
