package io.github.guillebot.streammux.contracts.kafka;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaStreamsApplicationIdsTest {

    @Test
    void buildsPrefixedLeaseScopedApplicationId() {
        assertEquals("streammux-onetrap-to-nrby-only-access-155", KafkaStreamsApplicationIds.applicationId("onetrap-to-nrby-only-access", 155));
    }

    @Test
    void rejectsBlankJobId() {
        assertThrows(IllegalArgumentException.class, () -> KafkaStreamsApplicationIds.applicationId(" ", 1));
    }
}
