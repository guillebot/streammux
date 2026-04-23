package io.github.guillebot.streammux.alarmstoztr.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves an output-mapping template against a raw input alarm.
 * Template has the same structure as the output message.
 * <ul>
 *   <li>Literal values are kept as-is.</li>
 *   <li>String values starting with {@code $input.} are replaced by the value at that path
 *       (e.g. {@code $input.alarm.severity}).</li>
 *   <li>{@code { "$input": "path", "$map": { "value": "mapped", "default": "fallback" } }}
 *       objects are translated through the map.</li>
 *   <li>Nested objects and arrays are resolved recursively.</li>
 * </ul>
 */
public final class OutputMappingResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutputMappingResolver.class);
    private static final String INPUT_PREFIX = "$input.";
    private static final Pattern INPUT_REF_PATTERN = Pattern.compile("\\$input\\.([a-zA-Z0-9_\\.\\[\\]=\\-\"']+)");
    private static final Pattern KEY_SELECTOR_SEGMENT_PATTERN = Pattern.compile("^([a-zA-Z0-9_]+)\\[key=([^\\]]+)\\]$");

    private OutputMappingResolver() {}

    @SuppressWarnings("unchecked")
    public static Object resolve(Object template, Map<String, Object> input) {
        if (template == null) {
            return null;
        }
        if (template instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) template;
            Object mapped = resolveValueMap(map, input);
            if (mapped != null) {
                return mapped;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : map.entrySet()) {
                Object v = resolve(e.getValue(), input);
                if (v != null) {
                    out.put(e.getKey(), v);
                }
            }
            return out;
        }
        if (template instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) template) {
                Object v = resolve(item, input);
                if (v != null) {
                    out.add(v);
                }
            }
            return out;
        }
        if (template instanceof String s) {
            if (s.contains(INPUT_PREFIX)) {
                return interpolateString(s, input);
            }
            return s;
        }
        return template;
    }

    private static String interpolateString(String s, Map<String, Object> input) {
        Matcher m = INPUT_REF_PATTERN.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String path = m.group(1);
            Object value = getByPath(input, path);
            String replacement = value != null ? value.toString() : path;
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static Object resolveValueMap(Map<String, Object> template, Map<String, Object> input) {
        Object pathObj = template.get("$input");
        Object mapObj = template.get("$map");
        if (!(pathObj instanceof String path) || path.isBlank() || !(mapObj instanceof Map<?, ?> valueMap)) {
            return null;
        }
        Object raw = getByPath(input, path.trim());
        String key = raw != null ? raw.toString().trim() : "";
        Object mapped = ((Map<String, Object>) valueMap).get(key);
        if (mapped == null) {
            mapped = ((Map<String, Object>) valueMap).get("default");
        }
        return mapped != null ? mapped.toString() : null;
    }

    @SuppressWarnings("unchecked")
    public static Object getByPath(Map<String, Object> root, String path) {
        if (path == null || path.isEmpty()) return null;
        Object current = root;
        for (String segment : path.split("\\.", -1)) {
            if (current == null) return null;
            if (current instanceof Map) {
                current = resolveSegment((Map<String, Object>) current, segment);
            } else if (current instanceof List<?> list) {
                current = resolveListIndex(list, segment);
            } else {
                return null;
            }
        }
        return current;
    }

    private static Object resolveListIndex(List<?> list, String segment) {
        segment = segment.trim();
        if (segment.isEmpty()) return null;
        int index;
        try {
            index = Integer.parseInt(segment, 10);
        } catch (NumberFormatException e) {
            return null;
        }
        if (index < 0 || index >= list.size()) return null;
        return list.get(index);
    }

    @SuppressWarnings("unchecked")
    private static Object resolveSegment(Map<String, Object> current, String segment) {
        Matcher m = KEY_SELECTOR_SEGMENT_PATTERN.matcher(segment);
        if (!m.matches()) {
            return current.get(segment);
        }
        String listField = m.group(1);
        String keyValue = unquote(m.group(2).trim());
        Object listObj = current.get(listField);
        if (!(listObj instanceof List<?> list)) {
            return null;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> itemMap) {
                Object key = ((Map<String, Object>) itemMap).get("key");
                if (key != null && keyValue.equals(key.toString())) {
                    return item;
                }
            }
        }
        return null;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            if (value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1);
            }
            if (value.startsWith("'") && value.endsWith("'")) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
