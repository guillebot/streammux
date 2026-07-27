package io.github.guillebot.streammux.contracts.event;

import io.github.guillebot.streammux.contracts.model.EventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JobEventTest {

    @Test
    void platformJobIdConstantIsStable() {
        assertEquals("_platform", JobEvent.PLATFORM_JOB_ID);
    }

    @Test
    void sessionEventTypeExistsForConsoleAudit() {
        assertNotNull(EventType.valueOf("SESSION"));
        assertEquals("SESSION", EventType.SESSION.name());
    }
}
