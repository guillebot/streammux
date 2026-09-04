package io.github.guillebot.streammux.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void jdbcDocumentsActivateOnlyWhenAuthOrConfigStudioIsOn() throws IOException {
        List<PropertySource<?>> docs = documents();
        assertTrue(docs.size() >= 3);

        PropertySource<?> auth = docs.get(1);
        assertEquals("streammux.auth.enabled=true",
            auth.getProperty("spring.config.activate.on-property"));
        assertEquals("${STREAMMUX_DATABASE_URL}", auth.getProperty("spring.datasource.url"));
        assertEquals("jdbc", auth.getProperty("spring.session.store-type"));

        PropertySource<?> studio = docs.get(2);
        assertEquals("streammux.config-studio.enabled=true",
            studio.getProperty("spring.config.activate.on-property"));
        assertEquals("${STREAMMUX_DATABASE_URL}", studio.getProperty("spring.datasource.url"));
        assertNotNull(studio.getProperty("spring.flyway.enabled"));
    }

    private static List<PropertySource<?>> documents() throws IOException {
        return new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));
    }
}
