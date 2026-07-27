package io.github.guillebot.streammux.orchestrator.service;

import io.github.guillebot.streammux.contracts.event.JobEvent;
import io.github.guillebot.streammux.contracts.model.EventType;
import io.github.guillebot.streammux.contracts.model.JobDefinition;
import io.github.guillebot.streammux.orchestrator.config.SiteIdentityProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class OrchestratorEventPublisher {
    private final KafkaOrchestratorPublisher publisher;
    private final SiteIdentityProperties siteIdentity;

    public OrchestratorEventPublisher(KafkaOrchestratorPublisher publisher, SiteIdentityProperties siteIdentity) {
        this.publisher = publisher;
        this.siteIdentity = siteIdentity;
    }

    public void publish(String jobId, long jobVersion, EventType eventType, String message, Map<String, Object> attributes) {
        JobEvent event = new JobEvent(
            UUID.randomUUID().toString(),
            jobId,
            jobVersion,
            eventType,
            Instant.now(),
            siteIdentity.siteId(),
            siteIdentity.instanceId(),
            message,
            attributes,
            siteIdentity.instanceId()
        );
        publisher.publishEvent(event);
    }

    public void publishForDefinition(JobDefinition definition, EventType eventType, String message, Map<String, Object> attributes) {
        publish(definition.jobId(), definition.jobVersion(), eventType, message, attributes);
    }
}
