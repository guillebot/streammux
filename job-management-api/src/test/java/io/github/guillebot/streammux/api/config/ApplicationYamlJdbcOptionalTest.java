package io.github.guillebot.streammux.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Production (auth off, no Postgres) must not bind an empty datasource URL or
 * JDBC sessions — that crash-loops job-management-api.
 */
class ApplicationYamlJdbcOptionalTest {

    @Test
    void defaultDocumentDoesNotConfigureJdbc() throws IOException {
        PropertySource<?> defaults = documents().get(0);
        assertNull(defaults.getProperty("spring.datasource.url"));
        assertNull(defaults.getProperty("spring.session.store-type"));
        assertEquals(Boolean.FALSE, defaults.getProperty("spring.flyway.enabled"));
        assertEquals(Boolean.FALSE, defaults.getProperty("management.health.db.enabled"));
        assertEquals("${STREAMMUX_AUTH_ENABLED:false}", defaults.getProperty("streammux.auth.enabled"));
    }

    @Test
    void jdbcDocumentActivatesOnlyOnAuthProfile() throws IOException {
        List<PropertySource<?>> docs = documents();
        assertEquals(2, docs.size());

        PropertySource<?> auth = docs.get(1);
        assertEquals("auth", auth.getProperty("spring.config.activate.on-profile"));
        assertEquals("${STREAMMUX_DATABASE_URL}", auth.getProperty("spring.datasource.url"));
        assertEquals("jdbc", auth.getProperty("spring.session.store-type"));
    }

    private static List<PropertySource<?>> documents() throws IOException {
        return new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));
    }
}
