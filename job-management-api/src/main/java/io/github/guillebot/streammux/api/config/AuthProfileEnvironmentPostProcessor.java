package io.github.guillebot.streammux.api.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

/**
 * Activates the {@code auth} profile when in-app auth is on so JDBC/Flyway/session
 * settings in {@code application.yml} (on-profile: auth) load. Spring Boot 3.5
 * does not support {@code spring.config.activate.on-property}.
 */
public final class AuthProfileEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String AUTH_PROFILE = "auth";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!authEnabled(environment)) {
            return;
        }
        for (String profile : environment.getActiveProfiles()) {
            if (AUTH_PROFILE.equals(profile)) {
                return;
            }
        }
        environment.addActiveProfile(AUTH_PROFILE);
    }

    static boolean authEnabled(ConfigurableEnvironment environment) {
        String envFlag = environment.getProperty("STREAMMUX_AUTH_ENABLED");
        if (StringUtils.hasText(envFlag)) {
            return Boolean.parseBoolean(envFlag.trim());
        }
        return Boolean.parseBoolean(environment.getProperty("streammux.auth.enabled", "false"));
    }
}
