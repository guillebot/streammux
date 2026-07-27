package io.github.guillebot.streammux.orchestrator.service;

import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.DesiredJobState;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.contracts.model.JobType;
import io.github.guillebot.streammux.contracts.model.LeasePolicy;
import io.github.guillebot.streammux.orchestrator.config.SiteIdentityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrchestratorEventPublisherTest {

    @Mock
    private KafkaOrchestratorPublisher publisher;

    @Test
    void publishIncludesSiteIdentityAndAttributes() {
        OrchestratorEventPublisher eventPublisher = new OrchestratorEventPublisher(
            publisher,
            new SiteIdentityProperties("rednet", "kstreams1")
        );

        eventPublisher.publish("job-1", 3, EventType.STARTED, "Runner started", Map.of("leaseEpoch", 7L));

        ArgumentCaptor<JobEvent> captor = ArgumentCaptor.forClass(JobEvent.class);
        verify(publisher).publishEvent(captor.capture());
        JobEvent event = captor.getValue();
        assertEquals("job-1", event.jobId());
        assertEquals(3, event.jobVersion());
        assertEquals(EventType.STARTED, event.eventType());
        assertEquals("rednet", event.siteId());
        assertEquals("kstreams1", event.instanceId());
        assertEquals("kstreams1", event.actor());
        assertEquals(7L, event.attributes().get("leaseEpoch"));
    }

    @Test
    void publishForDefinitionUsesDefinitionVersion() {
        OrchestratorEventPublisher eventPublisher = new OrchestratorEventPublisher(
            publisher,
            new SiteIdentityProperties("onelab", "instance-a")
        );
        JobDefinition definition = new JobDefinition(
            "job-9",
            11,
            JobType.ROUTE_APP,
            DesiredJobState.ACTIVE,
            1,
            "site-a",
            LeasePolicy.defaults(),
            1,
            null,
            null,
            null,
            Map.of(),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            "tester"
        );

        eventPublisher.publishForDefinition(definition, EventType.CLAIMED, "Lease claimed", Map.of());

        ArgumentCaptor<JobEvent> captor = ArgumentCaptor.forClass(JobEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertEquals(11, captor.getValue().jobVersion());
        assertEquals(EventType.CLAIMED, captor.getValue().eventType());
    }
}
