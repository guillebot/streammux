package io.github.guillebot.streammux.alarmstoztr.decoder;

import io.github.guillebot.streammux.alarmstoztr.filter.FilterEngine;
import io.github.guillebot.streammux.alarmstoztr.mapping.OutputMappingResolver;

import java.util.Map;

/**
 * Decoder that first runs the filter engine: only alarms matching a rule are forwarded,
 * using the mapping template selected by the first matching rule (or the filter's default).
 */
public final class FilteringDecoder implements AlarmDecoder {

    private final FilterEngine filterEngine;
    private final Map<String, Map<String, Object>> templatesByName;

    public FilteringDecoder(FilterEngine filterEngine, Map<String, Map<String, Object>> templatesByName) {
        this.filterEngine = filterEngine;
        this.templatesByName = templatesByName;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> decode(Map<String, Object> rawAlarm) {
        return filterEngine.selectMapping(rawAlarm)
            .map(templatesByName::get)
            .map(template -> {
                Object resolved = OutputMappingResolver.resolve(template, rawAlarm);
                return resolved instanceof Map ? (Map<String, Object>) resolved : null;
            })
            .orElse(null);
    }
}
