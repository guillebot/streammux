package io.github.guillebot.streammux.alarmstoztr.filter;

import io.github.guillebot.streammux.contracts.config.AlarmsToZtrFilter;
import io.github.guillebot.streammux.contracts.config.AlarmsToZtrFilterRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterEngineTest {

    @Test
    void selectMapping_usesRuleSpecificMapping_whenRuleMatches() {
        AlarmsToZtrFilter filter = new AlarmsToZtrFilter(
            "default",
            List.of(new AlarmsToZtrFilterRule("alarm.key", "regex", "^CONFIG", null, "configmap"))
        );
        FilterEngine engine = new FilterEngine(filter);

        Optional<String> mapping = engine.selectMapping(Map.of("alarm", Map.of("key", "CONFIG_CHANGE_1")));

        assertTrue(mapping.isPresent());
        assertEquals("configmap", mapping.get());
    }

    @Test
    void selectMapping_usesDefaultMapping_whenRuleMatchesWithoutOverride() {
        AlarmsToZtrFilter filter = new AlarmsToZtrFilter(
            "default",
            List.of(new AlarmsToZtrFilterRule("alarm.severity", "in", null, List.of("CRITICAL", "MAJOR"), null))
        );
        FilterEngine engine = new FilterEngine(filter);

        Optional<String> mapping = engine.selectMapping(Map.of("alarm", Map.of("severity", "MAJOR")));

        assertTrue(mapping.isPresent());
        assertEquals("default", mapping.get());
    }

    @Test
    void selectMapping_returnsEmpty_whenNoRuleMatches() {
        AlarmsToZtrFilter filter = new AlarmsToZtrFilter(
            "default",
            List.of(new AlarmsToZtrFilterRule("alarm.severity", "eq", "CRITICAL", null, null))
        );
        FilterEngine engine = new FilterEngine(filter);

        Optional<String> mapping = engine.selectMapping(Map.of("alarm", Map.of("severity", "MINOR")));

        assertTrue(mapping.isEmpty());
    }

    @Test
    void selectMapping_usesFirstMatchingRule() {
        AlarmsToZtrFilter filter = new AlarmsToZtrFilter(
            "default",
            List.of(
                new AlarmsToZtrFilterRule("alarm.severity", "exists", true, null, "first"),
                new AlarmsToZtrFilterRule("alarm.severity", "eq", "CRITICAL", null, "second")
            )
        );
        FilterEngine engine = new FilterEngine(filter);

        Optional<String> mapping = engine.selectMapping(Map.of("alarm", Map.of("severity", "CRITICAL")));

        assertTrue(mapping.isPresent());
        assertEquals("first", mapping.get());
    }

    @Test
    void selectMapping_neAndNotIn_invertBehavior() {
        AlarmsToZtrFilter neFilter = new AlarmsToZtrFilter(
            "default",
            List.of(new AlarmsToZtrFilterRule("alarm.severity", "ne", "CLEARED", null, null))
        );
        assertTrue(new FilterEngine(neFilter).selectMapping(Map.of("alarm", Map.of("severity", "CRITICAL"))).isPresent());

        AlarmsToZtrFilter notInFilter = new AlarmsToZtrFilter(
            "default",
            List.of(new AlarmsToZtrFilterRule("alarm.severity", "not_in", null, List.of("CLEARED", "WARNING"), null))
        );
        assertTrue(new FilterEngine(notInFilter).selectMapping(Map.of("alarm", Map.of("severity", "CRITICAL"))).isPresent());
    }
}
