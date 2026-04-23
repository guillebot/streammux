package io.github.guillebot.streammux.contracts.config;

import java.util.List;

public record AlarmsToZtrFilter(String defaultMappingName, List<AlarmsToZtrFilterRule> rules) {}
