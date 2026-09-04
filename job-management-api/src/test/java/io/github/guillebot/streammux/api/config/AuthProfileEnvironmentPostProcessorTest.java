package io.github.guillebot.streammux.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthProfileEnvironmentPostProcessorTest {

    private final AuthProfileEnvironmentPostProcessor processor = new AuthProfileEnvironmentPostProcessor();

    @Test
    void addsAuthProfileWhenEnvFlagIsTrue() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("STREAMMUX_AUTH_ENABLED", "true");
        processor.postProcessEnvironment(env, new SpringApplication());
        assertArrayEquals(new String[] {"auth"}, env.getActiveProfiles());
    }

    @Test
    void doesNotAddAuthProfileWhenAuthIsOff() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("STREAMMUX_AUTH_ENABLED", "false");
        processor.postProcessEnvironment(env, new SpringApplication());
        assertEquals(0, env.getActiveProfiles().length);
    }

    @Test
    void doesNotDuplicateAuthProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("auth", "prod");
        env.setProperty("STREAMMUX_AUTH_ENABLED", "true");
        processor.postProcessEnvironment(env, new SpringApplication());
        assertArrayEquals(new String[] {"auth", "prod"}, env.getActiveProfiles());
    }
}
