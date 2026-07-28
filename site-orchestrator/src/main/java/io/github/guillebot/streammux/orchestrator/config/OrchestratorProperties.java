package io.github.guillebot.streammux.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "streammux.orchestrator")
public record OrchestratorProperties(
    long reconcileIntervalMs,
    long runnerRestartDelayMs
) {}
