package io.github.guillebot.streammux.contracts.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicValidationPolicyTest {

    @Test
    void unrestrictedAllowsAnyNonBlankTopic() {
        TopicValidationPolicy policy = TopicValidationPolicy.unrestricted();

        assertTrue(policy.isInputTopicAllowed("anything"));
        assertTrue(policy.isOutputTopicAllowed("net.optimum.example"));
        assertFalse(policy.isInputTopicAllowed(""));
        assertFalse(policy.isInputTopicAllowed("   "));
        assertFalse(policy.isInputTopicAllowed(null));
    }

    @Test
    void emptyAllowlistsBehaveLikeUnrestricted() {
        TopicValidationPolicy policy = new TopicValidationPolicy(
            List.of(), List.of(), List.of(), List.of());

        assertTrue(policy.isInputTopicAllowed("lab.optimum.foo"));
        assertTrue(policy.isOutputTopicAllowed("com.optimum.bar"));
    }

    @Test
    void exactAndPrefixAllowlistsAreEnforced() {
        TopicValidationPolicy policy = new TopicValidationPolicy(
            List.of("exact.in"),
            List.of("net.optimum."),
            List.of("exact.out"),
            List.of("net.optimum.")
        );

        assertTrue(policy.isInputTopicAllowed("exact.in"));
        assertTrue(policy.isInputTopicAllowed("net.optimum.alarms"));
        assertFalse(policy.isInputTopicAllowed("lab.optimum.alarms"));
        assertTrue(policy.isOutputTopicAllowed("exact.out"));
        assertTrue(policy.isOutputTopicAllowed("net.optimum.out"));
        assertFalse(policy.isOutputTopicAllowed("com.optimum.out"));
    }

    @Test
    void sanitizeDropsBlankAndDuplicates() {
        java.util.ArrayList<String> exact = new java.util.ArrayList<>();
        exact.add(" a ");
        exact.add("a");
        exact.add("");
        exact.add(null);

        TopicValidationPolicy policy = new TopicValidationPolicy(
            exact,
            List.of(" pref. "),
            List.of(),
            List.of()
        );

        assertTrue(policy.isInputTopicAllowed("a"));
        assertTrue(policy.isInputTopicAllowed("pref.x"));
        assertFalse(policy.isInputTopicAllowed("other"));
    }
}
