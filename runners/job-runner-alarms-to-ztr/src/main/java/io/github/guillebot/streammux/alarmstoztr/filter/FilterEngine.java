package io.github.guillebot.streammux.alarmstoztr.filter;

import io.github.guillebot.streammux.alarmstoztr.mapping.OutputMappingResolver;
import io.github.guillebot.streammux.contracts.config.AlarmsToZtrFilter;
import io.github.guillebot.streammux.contracts.config.AlarmsToZtrFilterRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Evaluates filter rules against a raw alarm. First matching rule wins.
 * Returns the mapping name to use (rule-specific {@code mappingName} or filter {@code defaultMappingName}),
 * or empty if no rule matches.
 */
public final class FilterEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(FilterEngine.class);

    private final AlarmsToZtrFilter filter;

    public FilterEngine(AlarmsToZtrFilter filter) {
        this.filter = filter;
    }

    public Optional<String> selectMapping(Map<String, Object> rawAlarm) {
        if (filter == null || filter.rules() == null) {
            return Optional.empty();
        }
        for (AlarmsToZtrFilterRule rule : filter.rules()) {
            if (matches(rule, rawAlarm)) {
                String mapping = rule.mappingName() != null && !rule.mappingName().isBlank()
                    ? rule.mappingName()
                    : filter.defaultMappingName();
                if (mapping != null) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Filter match: path={} op={} -> mapping={}", rule.path(), rule.op(), mapping);
                    }
                    return Optional.of(mapping);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean matches(AlarmsToZtrFilterRule rule, Map<String, Object> rawAlarm) {
        if (rule == null || rule.path() == null || rule.path().isBlank() || rule.op() == null) {
            return false;
        }
        Object actual = OutputMappingResolver.getByPath(rawAlarm, rule.path().trim());
        return switch (rule.op()) {
            case "eq" -> eq(actual, rule.value());
            case "ne", "not_eq" -> !eq(actual, rule.value());
            case "in" -> in(actual, rule.values());
            case "not_in" -> rule.values() != null && !in(actual, rule.values());
            case "regex" -> regex(actual, rule.value());
            case "exists" -> rule.value() == null ? actual != null : Boolean.TRUE.equals(rule.value()) && actual != null;
            default -> {
                LOGGER.warn("Unknown filter op: {} (path={})", rule.op(), rule.path());
                yield false;
            }
        };
    }

    private static boolean eq(Object actual, Object expected) {
        if (expected == null) return actual == null;
        if (actual == null) return false;
        return actual.toString().equals(expected.toString());
    }

    private static boolean in(Object actual, List<?> allowed) {
        if (allowed == null || allowed.isEmpty()) return false;
        if (actual == null) return false;
        String a = actual.toString();
        for (Object o : allowed) {
            if (o != null && a.equals(o.toString())) return true;
        }
        return false;
    }

    private static boolean regex(Object actual, Object patternObj) {
        if (patternObj == null || patternObj.toString().isBlank()) return false;
        if (actual == null) return false;
        try {
            Pattern p = Pattern.compile(patternObj.toString().trim());
            return p.matcher(actual.toString()).find();
        } catch (Exception e) {
            LOGGER.warn("Invalid regex in filter: {}", patternObj, e);
            return false;
        }
    }
}
