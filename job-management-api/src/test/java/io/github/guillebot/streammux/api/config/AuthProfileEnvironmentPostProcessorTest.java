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
    void addsAuthProfileWhenConfigStudioIsEnabled() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("CONFIG_STUDIO_ENABLED", "true");
        processor.postProcessEnvironment(env, new SpringApplication());
        assertArrayEquals(new String[] {"auth"}, env.getActiveProfiles());
    }

    @Test
    void doesNotDuplicateAuthProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("auth", "prod");
        env.setProperty("STREAMMUX_AUTH_ENABLED", "true");
        processor.postProcessEnvironment(env, new SpringApplication());
        assertArrayEquals(new String[] {"auth", "prod"}, env.getActiveProfiles());
    }

    @Test
    void pinsBoot4JdbcExcludesOverStaleEnvironmentValueWhenAuthIsOff() {
        MockEnvironment env = new MockEnvironment();
        // Boot 3 class names as still shipped by older deployment env files.
        env.setProperty("spring.autoconfigure.exclude",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration");
        processor.postProcessEnvironment(env, new SpringApplication());
        String[] excludes = env.getProperty("spring.autoconfigure.exclude", String[].class);
        assertArrayEquals(new String[] {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration"
        }, excludes);
    }

    @Test
    void pinsJdbcExcludesWhenEnvironmentValueIsEmpty() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.autoconfigure.exclude", "");
        processor.postProcessEnvironment(env, new SpringApplication());
        assertEquals(AuthProfileEnvironmentPostProcessor.JDBC_AUTOCONFIGURATION_EXCLUDES,
                env.getProperty("spring.autoconfigure.exclude"));
    }

    @Test
    void clearsJdbcExcludesWhenAuthIsOn() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("STREAMMUX_AUTH_ENABLED", "true");
        env.setProperty("spring.autoconfigure.exclude",
                AuthProfileEnvironmentPostProcessor.JDBC_AUTOCONFIGURATION_EXCLUDES);
        processor.postProcessEnvironment(env, new SpringApplication());
        assertEquals("", env.getProperty("spring.autoconfigure.exclude"));
    }
}
