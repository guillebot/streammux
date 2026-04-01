package io.github.guillebot.streammux.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "streammux.site")
public record SiteIdentityProperties(String siteId, String instanceId) {}
