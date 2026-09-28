package io.github.guillebot.streammux.api.config;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Activates the {@code auth} profile when in-app auth or Config Studio is on so
 * JDBC/Flyway/session settings in {@code application.yml} (on-profile: auth) load.
 * Config Studio persists Git sync state in Postgres ({@code V2__config_studio.sql}).
 * Spring Boot does not support {@code spring.config.activate.on-property}.
 *
 * <p>It also pins {@code spring.autoconfigure.exclude} from a highest-precedence
 * property source. Deployments historically set {@code SPRING_AUTOCONFIGURE_EXCLUDE}
 * in the container environment; on Spring Boot 4 the JDBC/Flyway class names moved
 * and unknown names are silently ignored, so a stale or empty env value would let
 * {@code DataSourceAutoConfiguration} run without a URL and crash-loop the API.
 */
public final class AuthProfileEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String AUTH_PROFILE = "auth";
    public static final String AUTOCONFIGURE_EXCLUDE = "spring.autoconfigure.exclude";
    public static final String PROPERTY_SOURCE_NAME = "streammuxJdbcAutoConfiguration";
    public static final String JDBC_AUTOCONFIGURATION_EXCLUDES = String.join(",",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean jdbcRequired = authEnabled(environment) || configStudioEnabled(environment);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME,
                Map.of(AUTOCONFIGURE_EXCLUDE, jdbcRequired ? "" : JDBC_AUTOCONFIGURATION_EXCLUDES)));
        if (!jdbcRequired) {
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

    static boolean configStudioEnabled(ConfigurableEnvironment environment) {
        String envFlag = environment.getProperty("CONFIG_STUDIO_ENABLED");
        if (StringUtils.hasText(envFlag)) {
            return Boolean.parseBoolean(envFlag.trim());
        }
        return Boolean.parseBoolean(environment.getProperty("streammux.config-studio.enabled", "false"));
    }
}
