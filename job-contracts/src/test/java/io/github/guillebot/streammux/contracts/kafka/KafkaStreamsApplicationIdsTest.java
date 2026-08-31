package io.github.guillebot.streammux.contracts.kafka;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaStreamsApplicationIdsTest {

    @Test
    void buildsStablePrefixedApplicationId() {
        assertEquals("streammux-onetrap-to-nrby-only-access", KafkaStreamsApplicationIds.applicationId("onetrap-to-nrby-only-access"));
    }

    @Test
    void buildsLegacyEpochScopedApplicationId() {
        assertEquals(
            "streammux-onetrap-to-nrby-only-access-155",
            KafkaStreamsApplicationIds.legacyApplicationId("onetrap-to-nrby-only-access", 155)
        );
    }

    @Test
    void rejectsBlankJobId() {
        assertThrows(IllegalArgumentException.class, () -> KafkaStreamsApplicationIds.applicationId(" "));
    }
}
