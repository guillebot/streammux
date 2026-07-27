package io.github.guillebot.streammux.api.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaTopicCatalogServiceTest {
    @Test
    void filterTopicsExcludesInternalNamesAndAppliesAllowPredicate() {
        List<String> topics = List.of(
            "__consumer_offsets",
            "_schemas",
            "lab.optimum.events.in",
            "lab.optimum.events.out",
            "other.topic"
        );

        List<String> filtered = KafkaTopicCatalogService.filterTopics(
            topics,
            topic -> topic.startsWith("lab.optimum.")
        );

        assertEquals(List.of("lab.optimum.events.in", "lab.optimum.events.out"), filtered);
    }

    @Test
    void isInternalTopicTreatsUnderscorePrefixAsInternal() {
        assertTrue(KafkaTopicCatalogService.isInternalTopic("__consumer_offsets"));
        assertFalse(KafkaTopicCatalogService.isInternalTopic("lab.optimum.events.in"));
    }
}
