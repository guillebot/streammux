package io.github.guillebot.streammux.integration;

import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobRuntimeStatus;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LagMetrics;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.contracts.model.PayloadFormat;
import io.github.guillebot.streammux.contracts.model.RouteDefinition;
import io.github.guillebot.streammux.contracts.model.RuntimeState;
import io.github.guillebot.streammux.contracts.model.WorkerMetadata;
import io.github.guillebot.streammux.contracts.spi.JobRunner;
import io.github.guillebot.streammux.orchestrator.config.KafkaTopicProperties;
import io.github.guillebot.streammux.orchestrator.config.SiteIdentityProperties;
import io.github.guillebot.streammux.orchestrator.lease.LeaseManager;
import io.github.guillebot.streammux.orchestrator.runner.JobRunnerRegistry;
import io.github.guillebot.streammux.orchestrator.service.KafkaOrchestratorPublisher;
import io.github.guillebot.streammux.orchestrator.service.OrchestratorCoordinator;
import io.github.guillebot.streammux.orchestrator.service.OrchestratorService;
import io.github.guillebot.streammux.orchestrator.service.OrchestratorStateStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiSiteFailoverIT extends KafkaIntegrationSupport {

    @Test
    void failoverMovesLeaseToSecondSiteAfterHeartbeatExpiry() throws Exception {
        String prefix = "orchestrator-it-" + UUID.randomUUID();
        KafkaTopicProperties topics = new KafkaTopicProperties(
            prefix + "-definitions",
            prefix + "-leases",
            prefix + "-status",
            prefix + "-events",
            prefix + "-commands"
        );
        createTopics(List.of(topics.jobDefinitions(), topics.jobLeases(), topics.jobStatus()));

        KafkaConsumer<String, byte[]> definitionConsumer = createConsumer(topics.jobDefinitions());
        KafkaConsumer<String, byte[]> leaseConsumer = createConsumer(topics.jobLeases());

        KafkaTemplate<String, Object> kafkaTemplate = kafkaTemplate();

        JobRunner runnerA = runner("worker-a");
        JobRunner runnerB = runner("worker-b");
        OrchestratorCoordinator coordinatorA = coordinator("site-a", "instance-a", runnerA, kafkaTemplate, topics);
        OrchestratorCoordinator coordinatorB = coordinator("site-b", "instance-b", runnerB, kafkaTemplate, topics);

        JobDefinition definition = jobDefinition("job-1");
        kafkaTemplate.send(topics.jobDefinitions(), definition.jobId(), definition).get();

        ConsumerRecord<String, byte[]> definitionRecord = pollSingleRecord(definitionConsumer);
        coordinatorA.onJobDefinition(definitionRecord);

        ConsumerRecord<String, byte[]> firstLease = pollSingleRecord(leaseConsumer);
        coordinatorA.onJobLease(firstLease);
        coordinatorB.onJobLease(firstLease);
        coordinatorB.onJobDefinition(definitionRecord);

        verify(runnerA).start(definition, 1);
        verify(runnerB, never()).start(any(), anyLong());

        Thread.sleep(1200);
        coordinatorB.reconcileAll();

        ConsumerRecord<String, byte[]> secondLease = pollSingleRecord(leaseConsumer);
        coordinatorA.onJobLease(secondLease);
        coordinatorB.onJobLease(secondLease);

        verify(runnerB).start(definition, 2);
        verify(runnerA).stop("job-1");
    }

    private OrchestratorCoordinator coordinator(
        String siteId,
        String instanceId,
        JobRunner runner,
        KafkaTemplate<String, Object> kafkaTemplate,
        KafkaTopicProperties topics
    ) {
        when(runner.supports(any())).thenReturn(true);
        OrchestratorService orchestratorService = new OrchestratorService(
            new LeaseManager(new SiteIdentityProperties(siteId, instanceId)),
            new JobRunnerRegistry(List.of(runner))
        );
        return new OrchestratorCoordinator(
            new OrchestratorStateStore(),
            orchestratorService,
            new KafkaOrchestratorPublisher(kafkaTemplate, topics)
        );
    }

    private KafkaTemplate<String, Object> kafkaTemplate() {
        DefaultKafkaProducerFactory<String, Object> producerFactory = new DefaultKafkaProducerFactory<>(Map.of(
            org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
            org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringSerializer.class,
            org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
            JsonSerializer.ADD_TYPE_INFO_HEADERS, false
        ));
        return new KafkaTemplate<>(producerFactory);
    }

    private static JobRunner runner(String workerId) {
        JobRunner runner = mock(JobRunner.class);
        when(runner.status("job-1")).thenReturn(new JobRuntimeStatus(
            "job-1",
            1,
            RuntimeState.RUNNING,
            HealthState.HEALTHY,
            Instant.parse("2024-01-01T00:00:00Z"),
            new WorkerMetadata(workerId, "route-app", "RUNNING", Map.of()),
            null,
            new LagMetrics(0, 0, 0)
        ));
        return runner;
    }

    private static JobDefinition jobDefinition(String jobId) {
        return new JobDefinition(
            jobId,
            1,
            JobType.ROUTE_APP,
            DesiredJobState.ACTIVE,
            1,
            null,
            new LeasePolicy(1, 1, 10, true),
            1,
            new RouteAppConfig(
                "input-topic",
                PayloadFormat.JSON,
                PayloadFormat.JSON,
                null,
                List.of(new RouteDefinition("route-1", "message.type == \"ALARM\"", "alerts")),
                Map.of(),
                Map.of()
            ),
            Map.of(),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }
}
