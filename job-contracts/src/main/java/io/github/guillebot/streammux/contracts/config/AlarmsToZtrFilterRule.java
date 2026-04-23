package io.github.guillebot.streammux.contracts.config;

import java.util.List;

public record AlarmsToZtrFilterRule(String path, String op, Object value, List<Object> values, String mappingName) {}
