package io.github.guillebot.streammux.runner.support;

import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.LagMetrics;
import io.github.guillebot.streammux.contracts.model.RuntimeState;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.streams.KafkaStreams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaStreamsRunnerSupportTest {

    @Mock
    private KafkaStreams streams;

    @Test
    void statusForUnknownJobIsStoppedWithoutFailureReason() {
        KafkaStreamsRunnerSupport support = new KafkaStreamsRunnerSupport();

        JobRuntimeStatus status = support.status("missing", "route-app");

        assertEquals(RuntimeState.STOPPED, status.state());
        assertEquals(HealthState.UNKNOWN, status.health());
        assertNull(status.failureReason());
    }

    @Test
    void recordStartFailureSurfacesInStoppedStatus() {
        KafkaStreamsRunnerSupport support = new KafkaStreamsRunnerSupport();
        support.recordStartFailure("job-1", new IllegalStateException("bad config"));

        JobRuntimeStatus status = support.status("job-1", "route-app");

        assertEquals(RuntimeState.STOPPED, status.state());
        assertEquals("bad config", status.failureReason());
    }

    @Test
    void errorStateListenerMarksJobFailed() {
        KafkaStreamsRunnerSupport support = new KafkaStreamsRunnerSupport();
        ArgumentCaptor<KafkaStreams.StateListener> listenerCaptor = ArgumentCaptor.forClass(KafkaStreams.StateListener.class);
        when(streams.state()).thenReturn(KafkaStreams.State.CREATED, KafkaStreams.State.ERROR);
        doAnswer(invocation -> null).when(streams).setStateListener(listenerCaptor.capture());

        support.register("job-1", streams);
        listenerCaptor.getValue().onChange(KafkaStreams.State.ERROR, KafkaStreams.State.RUNNING);

        JobRuntimeStatus status = support.status("job-1", "route-app");

        assertEquals(RuntimeState.FAILED, status.state());
        assertEquals(HealthState.UNHEALTHY, status.health());
        assertEquals("Kafka Streams entered ERROR", status.failureReason());
    }

    @Test
    void stopClosesRegisteredStreams() {
        KafkaStreamsRunnerSupport support = new KafkaStreamsRunnerSupport();
        when(streams.state()).thenReturn(KafkaStreams.State.CREATED);
        doAnswer(invocation -> null).when(streams).setStateListener(any());

        support.register("job-1", streams);
        support.stop("job-1");

        verify(streams).close();
        assertEquals(RuntimeState.STOPPED, support.status("job-1", "route-app").state());
    }

    @Test
    void extractLagMetricsAggregatesPerStreamThreadConsumerMetrics() {
        Map<MetricName, Metric> metrics = new HashMap<>();
        metrics.put(
            fetchManagerMetric("records-consumed-total", Map.of("client-id", "job-1-StreamThread-1-consumer")),
            metricValue(700.0)
        );
        metrics.put(
            fetchManagerMetric("records-consumed-total", Map.of("client-id", "job-1-StreamThread-2-consumer")),
            metricValue(534.0)
        );
        metrics.put(
            fetchManagerMetric("records-consumed-rate", Map.of("client-id", "job-1-StreamThread-1-consumer")),
            metricValue(21.4)
        );
        metrics.put(
            fetchManagerMetric("records-consumed-rate", Map.of("client-id", "job-1-StreamThread-2-consumer")),
            metricValue(21.2)
        );
        metrics.put(
            new MetricName("records-lag-max", "consumer-fetch-manager-metrics", "", Map.of()),
            metricValue(99.0)
        );

        LagMetrics lag = KafkaStreamsRunnerSupport.extractLagMetrics(metrics);

        assertEquals(1234, lag.inputCount());
        assertEquals(43, lag.inputRatePerSecond());
        assertEquals(99, lag.inputLag());
        assertEquals(0, lag.outputCount());
        assertEquals(0, lag.outputRatePerSecond());
    }

    @Test
    void extractLagMetricsIgnoresDuplicateTopicAndProcessorSensors() {
        Map<MetricName, Metric> metrics = new HashMap<>();
        String clientId = "job-1-StreamThread-1-consumer";
        metrics.put(
            fetchManagerMetric("records-consumed-total", Map.of("client-id", clientId)),
            metricValue(5476.0)
        );
        metrics.put(
            fetchManagerMetric(
                "records-consumed-total",
                Map.of("client-id", clientId, "topic", "com.optimum.monitoring.alarmmanager.alarms")
            ),
            metricValue(5476.0)
        );
        metrics.put(
            fetchManagerMetric(
                "records-consumed-total",
                Map.of("client-id", clientId, "topic", "com.optimum.monitoring.alarmmanager.alarms", "partition", "0")
            ),
            metricValue(5476.0)
        );
        metrics.put(
            new MetricName(
                "records-consumed-total",
                "stream-topic-metrics",
                "",
                Map.of("thread-id", "1", "task-id", "0_0", "processor-node-id", "source", "topic", "input-topic")
            ),
            metricValue(5476.0)
        );
        metrics.put(
            fetchManagerMetric("records-consumed-rate", Map.of("client-id", clientId)),
            metricValue(6.4)
        );
        metrics.put(
            fetchManagerMetric(
                "records-consumed-rate",
                Map.of("client-id", clientId, "topic", "com.optimum.monitoring.alarmmanager.alarms")
            ),
            metricValue(6.4)
        );

        LagMetrics lag = KafkaStreamsRunnerSupport.extractLagMetrics(metrics);

        assertEquals(5476, lag.inputCount());
        assertEquals(6, lag.inputRatePerSecond());
    }

    @Test
    void extractLagMetricsAggregatesSinkProducedMetrics() {
        Map<MetricName, Metric> metrics = new HashMap<>();
        metrics.put(
            streamTopicMetric(
                "records-produced-total",
                Map.of("processor-node-id", "Sink-route-a", "topic", "net.optimum.output.a", "thread-id", "1", "task-id", "0_0")
            ),
            metricValue(1200.0)
        );
        metrics.put(
            streamTopicMetric(
                "records-produced-total",
                Map.of("processor-node-id", "Sink-route-b", "topic", "net.optimum.output.b", "thread-id", "1", "task-id", "0_1")
            ),
            metricValue(800.0)
        );
        metrics.put(
            streamTopicMetric(
                "records-produced-rate",
                Map.of("processor-node-id", "Sink-route-a", "topic", "net.optimum.output.a", "thread-id", "1", "task-id", "0_0")
            ),
            metricValue(12.4)
        );
        metrics.put(
            streamTopicMetric(
                "records-produced-rate",
                Map.of("processor-node-id", "Sink-route-b", "topic", "net.optimum.output.b", "thread-id", "1", "task-id", "0_1")
            ),
            metricValue(8.1)
        );
        metrics.put(
            streamTopicMetric(
                "records-produced-total",
                Map.of(
                    "processor-node-id",
                    "Sink-route-a",
                    "topic",
                    "net.optimum.output.a",
                    "partition",
                    "0",
                    "thread-id",
                    "1",
                    "task-id",
                    "0_0"
                )
            ),
            metricValue(1200.0)
        );

        LagMetrics lag = KafkaStreamsRunnerSupport.extractLagMetrics(metrics);

        assertEquals(2000, lag.outputCount());
        assertEquals(21, lag.outputRatePerSecond());
    }

    @Test
    void extractLagMetricsCombinesInputAndOutputForFilterJob() {
        Map<MetricName, Metric> metrics = new HashMap<>();
        String clientId = "onetrap-StreamThread-1-consumer";
        metrics.put(fetchManagerMetric("records-consumed-total", Map.of("client-id", clientId)), metricValue(1_085_330.0));
        metrics.put(fetchManagerMetric("records-consumed-rate", Map.of("client-id", clientId)), metricValue(975.2));
        metrics.put(
            streamTopicMetric(
                "records-produced-total",
                Map.of("processor-node-id", "Sink-filtered", "topic", "net.optimum.filtered", "thread-id", "1", "task-id", "0_0")
            ),
            metricValue(50_629.0)
        );
        metrics.put(
            streamTopicMetric(
                "records-produced-rate",
                Map.of("processor-node-id", "Sink-filtered", "topic", "net.optimum.filtered", "thread-id", "1", "task-id", "0_0")
            ),
            metricValue(44.6)
        );

        LagMetrics lag = KafkaStreamsRunnerSupport.extractLagMetrics(metrics);

        assertEquals(1_085_330, lag.inputCount());
        assertEquals(975, lag.inputRatePerSecond());
        assertEquals(50_629, lag.outputCount());
        assertEquals(45, lag.outputRatePerSecond());
    }

    private static MetricName fetchManagerMetric(String name, Map<String, String> tags) {
        return new MetricName(name, "consumer-fetch-manager-metrics", "", tags);
    }

    private static MetricName streamTopicMetric(String name, Map<String, String> tags) {
        return new MetricName(name, "stream-topic-metrics", "", tags);
    }

    private static Metric metricValue(Object value) {
        return new Metric() {
            @Override
            public MetricName metricName() {
                return null;
            }

            @Override
            public Object metricValue() {
                return value;
            }
        };
    }
}
