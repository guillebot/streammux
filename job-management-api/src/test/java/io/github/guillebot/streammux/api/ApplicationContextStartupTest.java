/*
 * (c) Optimum 2026
 * Guillermo Schimmel
 */

package io.github.guillebot.streammux.api;

import io.github.guillebot.streammux.api.config.AuthProfileEnvironmentPostProcessor;
import io.github.guillebot.streammux.api.service.KafkaJobCommandPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the full application context with the production environment shape
 * (prod profile, auth off, no Postgres, PLAINTEXT Kafka). Guards against
 * missing-bean startup failures that slice tests and hand-wired integration
 * tests cannot see — e.g. Kafka auto-configuration dropping out after a
 * Spring Boot major upgrade. Listener containers are kept stopped so no broker
 * is required.
 */
@SpringBootTest(properties = {
    "spring.profiles.active=prod",
    "spring.kafka.bootstrap-servers=localhost:9",
    "spring.kafka.listener.auto-startup=false",
    "streammux.topics.job-definitions=test.jobdefinitions",
    "streammux.topics.job-leases=test.jobleases",
    "streammux.topics.job-status=test.jobstatus",
    "streammux.topics.job-events=test.jobevents",
    "streammux.topics.job-commands=test.jobcommands",
    "STREAMMUX_API_PASSWORD=context-test",
    "STREAMMUX_AUTH_ENABLED=false",
    "streammux.health.lag-check-interval-ms=3600000"
})
class ApplicationContextStartupTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private ConfigurableEnvironment environment;

    @Test
    void contextLoadsWithKafkaProducerAndListeners() {
        assertNotNull(context.getBean(KafkaTemplate.class), "Kafka auto-configuration must provide a KafkaTemplate");
        assertNotNull(context.getBean(KafkaJobCommandPublisher.class));
        assertFalse(listenerRegistry.getListenerContainerIds().isEmpty(),
            "@KafkaListener endpoints must be registered (listener container factory present)");
    }

    @Test
    void jdbcAutoConfigurationIsExcludedWhenAuthIsOff() {
        assertTrue(environment.getPropertySources().contains(AuthProfileEnvironmentPostProcessor.PROPERTY_SOURCE_NAME),
            "AuthProfileEnvironmentPostProcessor must pin spring.autoconfigure.exclude");
        assertEquals(AuthProfileEnvironmentPostProcessor.JDBC_AUTOCONFIGURATION_EXCLUDES,
            environment.getProperty(AuthProfileEnvironmentPostProcessor.AUTOCONFIGURE_EXCLUDE));
        assertFalse(context.containsBean("dataSource"), "no DataSource may be auto-configured without Postgres");
    }
}
