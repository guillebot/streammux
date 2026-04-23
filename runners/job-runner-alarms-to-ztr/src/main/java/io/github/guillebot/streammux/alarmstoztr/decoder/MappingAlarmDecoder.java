package io.github.guillebot.streammux.alarmstoztr.decoder;

import io.github.guillebot.streammux.alarmstoztr.mapping.OutputMappingResolver;

import java.util.Map;

/**
 * Decoder that applies a single mapping template to every raw alarm.
 */
public final class MappingAlarmDecoder implements AlarmDecoder {

    private final Map<String, Object> mappingTemplate;

    public MappingAlarmDecoder(Map<String, Object> mappingTemplate) {
        this.mappingTemplate = mappingTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> decode(Map<String, Object> rawAlarm) {
        Object resolved = OutputMappingResolver.resolve(mappingTemplate, rawAlarm);
        if (!(resolved instanceof Map)) {
            return null;
        }
        return (Map<String, Object>) resolved;
    }
}
