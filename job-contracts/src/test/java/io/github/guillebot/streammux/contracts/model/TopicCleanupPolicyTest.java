package io.github.guillebot.streammux.contracts.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicCleanupPolicyTest {

    @Test
    void expectedForTopicKey() {
        assertEquals(TopicCleanupPolicy.COMPACT, TopicCleanupPolicy.expectedForTopicKey("jobDefinitions"));
        assertEquals(TopicCleanupPolicy.COMPACT, TopicCleanupPolicy.expectedForTopicKey("jobCatalog"));
        assertEquals(TopicCleanupPolicy.DELETE, TopicCleanupPolicy.expectedForTopicKey("jobLeases"));
        assertEquals(TopicCleanupPolicy.DELETE, TopicCleanupPolicy.expectedForTopicKey("jobEvents"));
    }

    @Test
    void matchesCompactPolicy() {
        assertTrue(TopicCleanupPolicy.matches("compact", TopicCleanupPolicy.COMPACT));
        assertTrue(TopicCleanupPolicy.matches("compact,delete", TopicCleanupPolicy.COMPACT));
        assertFalse(TopicCleanupPolicy.matches("delete", TopicCleanupPolicy.COMPACT));
        assertFalse(TopicCleanupPolicy.matches(null, TopicCleanupPolicy.COMPACT));
    }

    @Test
    void matchesDeletePolicy() {
        assertTrue(TopicCleanupPolicy.matches("delete", TopicCleanupPolicy.DELETE));
        assertTrue(TopicCleanupPolicy.matches(null, TopicCleanupPolicy.DELETE));
        assertFalse(TopicCleanupPolicy.matches("compact", TopicCleanupPolicy.DELETE));
        assertFalse(TopicCleanupPolicy.matches("compact,delete", TopicCleanupPolicy.DELETE));
    }

    @Test
    void okRequiresExistenceAndMatchingPolicy() {
        assertTrue(TopicCleanupPolicy.ok(true, "compact", TopicCleanupPolicy.COMPACT));
        assertFalse(TopicCleanupPolicy.ok(false, "compact", TopicCleanupPolicy.COMPACT));
        assertFalse(TopicCleanupPolicy.ok(true, "delete", TopicCleanupPolicy.COMPACT));
    }
}
