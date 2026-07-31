package io.github.guillebot.streammux.api.service;

import io.github.guillebot.streammux.api.config.KafkaTopicProperties;
import io.github.guillebot.streammux.contracts.model.TopicCleanupPolicy;
import io.github.guillebot.streammux.contracts.model.TopicNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlatformHealthServiceTest {
    private JobStateStore stateStore;
    private PlatformHealthService service;

    @BeforeEach
    void setUp() {
        stateStore = new JobStateStore();
        KafkaTopicProperties topics = KafkaTopicProperties.defaults();
        service = new PlatformHealthService("invalid-broker:9092", topics, stateStore);
    }

    @Test
    void snapshotReportsKafkaDownWhenBrokerUnreachable() {
        PlatformHealthService.PlatformHealth health = service.snapshot();

        assertNotNull(health.checkedAt());
        assertEquals("DOWN", health.status());
        assertEquals("DOWN", health.kafka().status());
        assertNotNull(health.kafka().detail());
        assertEquals("job-management-api", health.module().name());
        assertEquals("UP", health.module().status());
        assertEquals(0, health.readModel().jobCount());
    }

    @Test
    void snapshotIncludesConfiguredTopicNamesWhenKafkaDown() {
        PlatformHealthService.PlatformHealth health = service.snapshot();

        assertEquals(5, health.kafka().topics().size());
        assertEquals(TopicNames.JOB_DEFINITIONS, health.kafka().topics().getFirst().name());
    }

    @Test
    void snapshotTopicEntriesIncludeExpectedCleanupPolicyWhenKafkaDown() {
        PlatformHealthService.PlatformHealth health = service.snapshot();
        PlatformHealthService.TopicPresence jobDefinitions = health.kafka().topics().getFirst();

        assertFalse(jobDefinitions.exists());
        assertNull(jobDefinitions.cleanupPolicy());
        assertEquals(TopicCleanupPolicy.COMPACT, jobDefinitions.expected());
        assertFalse(jobDefinitions.ok());
    }

    @Test
    void worstStatusPreservesDegradedInsteadOfCollapsingToDown() {
        // Reachable Kafka with a topic that has the wrong cleanup policy should
        // surface at the platform level as DEGRADED, not DOWN — the fleet is
        // running, but an operator needs to fix a config drift.
        assertEquals("DEGRADED", PlatformHealthService.worstStatus("UP", "DEGRADED"));
        assertEquals("DOWN", PlatformHealthService.worstStatus("UP", "DOWN"));
        assertEquals("DOWN", PlatformHealthService.worstStatus("DEGRADED", "DOWN"));
        assertEquals("UP", PlatformHealthService.worstStatus("UP", "UP"));
    }
}
