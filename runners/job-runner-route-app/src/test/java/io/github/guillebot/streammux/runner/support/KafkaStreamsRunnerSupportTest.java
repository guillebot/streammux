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
    void extractLagMetricsAggregatesConsumerMetrics() {
        Map<MetricName, Metric> metrics = new HashMap<>();
        metrics.put(
            new MetricName("records-consumed-total", "consumer-metrics", "", Map.of()),
            metricValue(1234.0)
        );
        metrics.put(
            new MetricName("records-consumed-rate", "consumer-metrics", "", Map.of()),
            metricValue(42.6)
        );
        metrics.put(
            new MetricName("records-lag-max", "consumer-fetch-manager-metrics", "", Map.of()),
            metricValue(99.0)
        );

        LagMetrics lag = KafkaStreamsRunnerSupport.extractLagMetrics(metrics);

        assertEquals(1234, lag.processedCount());
        assertEquals(43, lag.outputRatePerSecond());
        assertEquals(99, lag.inputLag());
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
