package io.github.guillebot.streammux.orchestrator.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Fails fast on missing or blank Kafka, site identity, and topic settings; logs effective configuration at startup.
 */
public final class OrchestratorRequiredSettingsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final DeferredLog LOG = new DeferredLog();

    private static final List<RequiredProperty> REQUIRED = List.of(
        new RequiredProperty("spring.kafka.bootstrap-servers", "KAFKA_BOOTSTRAP_SERVERS"),
        new RequiredProperty("streammux.site.site-id", "STREAMMUX_SITE_ID"),
        new RequiredProperty("streammux.site.instance-id", "STREAMMUX_INSTANCE_ID"),
        new RequiredProperty("streammux.topics.job-definitions", "STREAMMUX_TOPIC_JOB_DEFINITIONS"),
        new RequiredProperty("streammux.topics.job-leases", "STREAMMUX_TOPIC_JOB_LEASES"),
        new RequiredProperty("streammux.topics.job-status", "STREAMMUX_TOPIC_JOB_STATUS"),
        new RequiredProperty("streammux.topics.job-events", "STREAMMUX_TOPIC_JOB_EVENTS"),
        new RequiredProperty("streammux.topics.job-commands", "STREAMMUX_TOPIC_JOB_COMMANDS")
    );

    static {
        DeferredLog.replay(LOG, OrchestratorRequiredSettingsEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> problems = new ArrayList<>();
        for (RequiredProperty req : REQUIRED) {
            String value = environment.getProperty(req.propertyKey());
            if (!isSet(value)) {
                problems.add(req.propertyKey() + " (set " + req.envName() + ")");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                "site-orchestrator cannot start: missing or blank required settings: " + String.join(", ", problems));
        }
        LOG.info(
            "site-orchestrator startup: siteId="
                + environment.getProperty("streammux.site.site-id")
                + " instanceId="
                + environment.getProperty("streammux.site.instance-id")
                + " kafka.bootstrap.servers="
                + environment.getProperty("spring.kafka.bootstrap-servers")
                + " topics definitions="
                + environment.getProperty("streammux.topics.job-definitions")
                + " leases="
                + environment.getProperty("streammux.topics.job-leases")
                + " status="
                + environment.getProperty("streammux.topics.job-status")
                + " events="
                + environment.getProperty("streammux.topics.job-events")
                + " commands="
                + environment.getProperty("streammux.topics.job-commands")
                + " reconcileIntervalMs="
                + environment.getProperty("streammux.orchestrator.reconcile-interval-ms"));
    }

    private record RequiredProperty(String propertyKey, String envName) {}

    private static boolean isSet(String value) {
        return StringUtils.hasText(value) && !value.contains("${");
    }
}
