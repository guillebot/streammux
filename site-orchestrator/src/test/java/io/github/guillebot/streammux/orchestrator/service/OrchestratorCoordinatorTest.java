package io.github.guillebot.streammux.orchestrator.service;

import io.github.guillebot.streammux.orchestrator.lease.LeaseManager;
import io.github.guillebot.streammux.orchestrator.metrics.StreammuxOrchestratorMetrics;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.listener.ConsumerSeekAware;

import java.util.Map;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrchestratorCoordinatorTest {

    @Mock
    private OrchestratorStateStore stateStore;
    @Mock
    private OrchestratorService orchestratorService;
    @Mock
    private LeaseManager leaseManager;
    @Mock
    private KafkaOrchestratorPublisher publisher;
    @Mock
    private StreammuxOrchestratorMetrics orchestratorMetrics;
    @Mock
    private ConsumerSeekAware.ConsumerSeekCallback callback;

    @Test
    void seeksToBeginningOnlyOnFirstAssignment() {
        OrchestratorCoordinator coordinator = new OrchestratorCoordinator(
            stateStore,
            orchestratorService,
            leaseManager,
            publisher,
            orchestratorMetrics
        );
        TopicPartition leases = new TopicPartition("jobleases", 0);

        coordinator.onPartitionsAssigned(Map.of(leases, 12L), callback);
        coordinator.onPartitionsAssigned(Map.of(leases, 40L), callback);

        verify(callback, times(1)).seekToBeginning("jobleases", 0);
    }

    @Test
    void rebalanceDoesNotSeekToBeginningWhenPartitionAlreadyAssigned() {
        OrchestratorCoordinator coordinator = new OrchestratorCoordinator(
            stateStore,
            orchestratorService,
            leaseManager,
            publisher,
            orchestratorMetrics
        );
        TopicPartition leases = new TopicPartition("jobleases", 0);

        coordinator.onPartitionsAssigned(Map.of(leases, 0L), callback);
        coordinator.onPartitionsAssigned(Map.of(leases, 99L), callback);

        verify(callback, times(1)).seekToBeginning("jobleases", 0);
        verify(callback, never()).seek("jobleases", 0, 99L);
    }
}
