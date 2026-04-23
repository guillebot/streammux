package io.github.guillebot.streammux.alarmstoztr.decoder;

import java.util.Map;

/**
 * Decodes a raw alarm (as a JSON-shaped map) into an output alarm map.
 * Implementations return {@code null} to drop the record.
 */
@FunctionalInterface
public interface AlarmDecoder {
    Map<String, Object> decode(Map<String, Object> rawAlarm);
}
