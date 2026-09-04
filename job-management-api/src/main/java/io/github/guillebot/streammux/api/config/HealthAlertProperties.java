package io.github.guillebot.streammux.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "streammux.health")
public record HealthAlertProperties(
    long lagWarnThreshold,
    long heartbeatStaleSeconds
) {
    public HealthAlertProperties() {
        this(10_000L, 300L);
    }
}
