/*
 * (c) Optimum 2026
 * Guillermo Schimmel
 */

package io.github.guillebot.streammux.orchestrator;

import io.github.guillebot.streammux.orchestrator.service.KafkaOrchestratorPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Boots the full orchestrator context with the production environment shape.
 * Guards against missing-bean startup failures (e.g. Kafka auto-configuration
 * absent after a Spring Boot major upgrade). Listener containers stay stopped
 * so no broker is required.
 */
@SpringBootTest(properties = {
    "spring.profiles.active=prod",
    "spring.kafka.bootstrap-servers=localhost:9",
    "spring.kafka.listener.auto-startup=false",
    "streammux.site.site-id=test-site",
    "streammux.site.instance-id=test-instance",
    "streammux.topics.job-definitions=test.jobdefinitions",
    "streammux.topics.job-leases=test.jobleases",
    "streammux.topics.job-status=test.jobstatus",
    "streammux.topics.job-events=test.jobevents",
    "streammux.topics.job-commands=test.jobcommands",
    "streammux.orchestrator.reconcile-interval-ms=3600000"
})
class ApplicationContextStartupTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Test
    void contextLoadsWithKafkaProducerAndListeners() {
        assertNotNull(context.getBean(KafkaTemplate.class), "Kafka auto-configuration must provide a KafkaTemplate");
        assertNotNull(context.getBean(KafkaOrchestratorPublisher.class));
        assertFalse(listenerRegistry.getListenerContainerIds().isEmpty(),
            "@KafkaListener endpoints must be registered (listener container factory present)");
    }
}
