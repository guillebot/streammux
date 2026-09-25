package io.github.guillebot.streammux.integration;

import io.github.guillebot.streammux.contracts.config.RouteAppConfig;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.HealthState;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobLease;
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
import io.github.guillebot.streammux.orchestrator.config.OrchestratorProperties;
import io.github.guillebot.streammux.orchestrator.config.SiteIdentityProperties;
import io.github.guillebot.streammux.orchestrator.lease.LeaseManager;
import io.github.guillebot.streammux.orchestrator.runner.JobRunnerRegistry;
import io.github.guillebot.streammux.orchestrator.service.KafkaOrchestratorPublisher;
import io.github.guillebot.streammux.orchestrator.metrics.StreammuxOrchestratorMetrics;
import io.github.guillebot.streammux.orchestrator.service.OrchestratorCoordinator;
import io.github.guillebot.streammux.orchestrator.service.OrchestratorEventPublisher;
import io.github.guillebot.streammux.orchestrator.service.OrchestratorService;
import io.github.guillebot.streammux.orchestrator.service.OrchestratorStateStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
        JobLease claimed = LEASE_MAPPER.readValue(firstLease.value(), JobLease.class);
        coordinatorA.onJobLease(firstLease);
        coordinatorB.onJobLease(firstLease);
        coordinatorB.onJobDefinition(definitionRecord);

        verify(runnerA, timeout(10_000)).start(eq(definition), anyLong());
        verify(runnerB, never()).start(any(), anyLong());

        long waitForExpiryMs = Duration.between(Instant.now(), claimed.leaseExpiresAt().plusMillis(1_200)).toMillis();
        if (waitForExpiryMs > 0) {
            Thread.sleep(waitForExpiryMs);
        }
        coordinatorB.reconcileAll();

        ConsumerRecord<String, byte[]> secondLease = pollUntilLeaseOwner(leaseConsumer, "site-b");
        JobLease failedOver = LEASE_MAPPER.readValue(secondLease.value(), JobLease.class);
        coordinatorA.onJobLease(secondLease);
        coordinatorB.onJobLease(secondLease);

        verify(runnerB).start(definition, failedOver.leaseEpoch());
        verify(runnerA).stop("job-1");
    }


    private static final ObjectMapper LEASE_MAPPER = new ObjectMapper();

    private ConsumerRecord<String, byte[]> pollUntilLeaseOwner(KafkaConsumer<String, byte[]> consumer, String siteId) throws Exception {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(250));
            for (ConsumerRecord<String, byte[]> record : records) {
                JobLease lease = LEASE_MAPPER.readValue(record.value(), JobLease.class);
                if (siteId.equals(lease.leaseOwnerSite())) {
                    return record;
                }
            }
        }
        throw new AssertionError("Timed out waiting for lease owner " + siteId);
    }

    private OrchestratorCoordinator coordinator(
        String siteId,
        String instanceId,
        JobRunner runner,
        KafkaTemplate<String, Object> kafkaTemplate,
        KafkaTopicProperties topics
    ) {
        when(runner.supports(any())).thenReturn(true);
        LeaseManager leaseManager = new LeaseManager(new SiteIdentityProperties(siteId, instanceId));
        KafkaOrchestratorPublisher publisher = new KafkaOrchestratorPublisher(kafkaTemplate, topics);
        OrchestratorEventPublisher eventPublisher = new OrchestratorEventPublisher(publisher, new SiteIdentityProperties(siteId, instanceId));
        StreammuxOrchestratorMetrics orchestratorMetrics = org.mockito.Mockito.mock(StreammuxOrchestratorMetrics.class);
        OrchestratorService orchestratorService = new OrchestratorService(
            leaseManager,
            new SiteIdentityProperties(siteId, instanceId),
            new JobRunnerRegistry(List.of(runner)),
            eventPublisher,
            new OrchestratorProperties(5000, 0),
            orchestratorMetrics
        );
        return new OrchestratorCoordinator(
            new OrchestratorStateStore(),
            orchestratorService,
            leaseManager,
            publisher,
            orchestratorMetrics
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
            new LagMetrics(0, 0, 0, 0, 0)
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
            new LeasePolicy(1, 5, 0, true),
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
            null,
            null,
            Map.of(),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );
    }
}
