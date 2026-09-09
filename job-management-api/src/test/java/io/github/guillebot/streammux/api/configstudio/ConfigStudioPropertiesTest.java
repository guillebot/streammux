package io.github.guillebot.streammux.api.configstudio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStudioPropertiesTest {

    @Test
    void notReadyWhenDisabled() {
        ConfigStudioProperties props = sample(false, "dmr4013905/techarchitecture/techarchitecture/streammux-configs", "token");
        assertFalse(props.ready());
        assertTrue(props.configurationIssues().isEmpty());
    }

    @Test
    void reportsMissingGitlabSettingsWhenEnabled() {
        ConfigStudioProperties props = sample(true, "", "");
        assertFalse(props.ready());
        assertEquals(2, props.configurationIssues().size());
        assertTrue(props.configurationIssues().get(0).contains("CONFIG_STUDIO_GITLAB_PROJECT_ID"));
        assertTrue(props.configurationIssues().get(1).contains("CONFIG_STUDIO_GITLAB_TOKEN"));
    }

    @Test
    void readyWhenEnabledWithProjectAndToken() {
        ConfigStudioProperties props = sample(true, "86097572", "glpat-test");
        assertTrue(props.ready());
        assertTrue(props.configurationIssues().isEmpty());
        assertEquals("https://gitlab.com/projects/86097572", props.gitlabProjectUrl());
    }

    @Test
    void defaultEnvironmentPrefersExplicitThenLabel() {
        ConfigStudioProperties props = sample(true, "86097572", "glpat-test");
        assertEquals("prod", props.resolvedDefaultEnvironment("ignored"));
        ConfigStudioProperties unlabeled = new ConfigStudioProperties(
            true,
            "https://gitlab.com",
            "86097572",
            "glpat-test",
            "main",
            "",
            List.of("onelab", "prod"),
            0,
            false,
            List.of("prod"),
            false,
            ""
        );
        assertEquals("onelab", unlabeled.resolvedDefaultEnvironment("ONELAB"));
    }

    private static ConfigStudioProperties sample(boolean enabled, String projectId, String token) {
        return new ConfigStudioProperties(
            enabled,
            "https://gitlab.com",
            projectId,
            token,
            "main",
            "prod",
            List.of("onelab", "stage", "prod"),
            0,
            false,
            List.of("prod"),
            false,
            ""
        );
    }
}
