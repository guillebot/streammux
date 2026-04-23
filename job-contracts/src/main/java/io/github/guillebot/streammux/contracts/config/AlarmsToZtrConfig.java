package io.github.guillebot.streammux.contracts.config;

import java.util.List;
import java.util.Map;

public record AlarmsToZtrConfig(
    String inputTopic,
    String outputTopic,
    String source,
    Double sampleRate,
    Map<String, Map<String, Object>> mappings,
    String defaultMappingName,
    AlarmsToZtrFilter filter,
    Map<String, String> streamProperties
) {}
