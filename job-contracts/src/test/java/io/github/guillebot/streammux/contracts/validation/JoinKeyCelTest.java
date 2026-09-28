package io.github.guillebot.streammux.contracts.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinKeyCelTest {

    @Test
    void normalizesHyphenatedAccountWithTwoDigitPad() {
        JoinKeyCel.Compiled compiled = JoinKeyCel.compile(JoinKeyCel.HYPHENATED_ACCOUNT_CEL);
        assertEquals("770793819901", compiled.apply("7707-938199-1"));
        assertEquals("770793819912", compiled.apply("7707-938199-12"));
    }

    @Test
    void identityWhenUnsplittable() {
        JoinKeyCel.Compiled compiled = JoinKeyCel.compile(JoinKeyCel.HYPHENATED_ACCOUNT_CEL);
        assertEquals("770793819901", compiled.apply("770793819901"));
    }

    @Test
    void acctnum12CoversThreeAccountNumShapes() {
        JoinKeyCel.Compiled compiled = JoinKeyCel.compile(JoinKeyCel.ACCTNUM_12_CEL);
        assertEquals("770793819901", compiled.apply("7707-938199-1"));
        assertEquals("770793819912", compiled.apply("7707-938199-12"));
        assertEquals("770793819901", compiled.apply("770793819901ABC"));
    }

    @Test
    void digitsOnlyStripsNonDigits() {
        JoinKeyCel.Compiled compiled = JoinKeyCel.compile(JoinKeyCel.DIGITS_ONLY_CEL);
        assertEquals("770793819912", compiled.apply("7707-938199-12"));
        assertEquals("770793819901", compiled.apply("770793819901ABC"));
    }

    @Test
    void rejectsInvalidExpression() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> JoinKeyCel.compile("key +++"));
        assertTrue(ex.getMessage().contains("joinKeyCel"));
    }

    @Test
    void evalFailureReturnsNull() {
        JoinKeyCel.Compiled compiled = JoinKeyCel.compile("key.split(\"-\")[9]");
        assertNull(compiled.apply("a-b"));
    }
}
