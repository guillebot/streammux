package io.github.guillebot.streammux.api.configstudio;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Always registered so the UI can distinguish disabled vs misconfigured deployments
 * even when {@link ConfigStudioController} beans are off.
 */
@RestController
@RequestMapping("/api/config-studio")
public class ConfigStudioStatusController {

    private final ConfigStudioProperties props;
    private final ObjectProvider<ConfigStudioService> service;
    private final String deploymentLabel;

    public ConfigStudioStatusController(
        ConfigStudioProperties props,
        ObjectProvider<ConfigStudioService> service,
        @Value("${STREAMMUX_DEPLOYMENT_LABEL:}") String deploymentLabel
    ) {
        this.props = props;
        this.service = service;
        this.deploymentLabel = deploymentLabel == null ? "" : deploymentLabel.trim();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        ConfigStudioService live = service.getIfAvailable();
        if (live != null) {
            return live.status();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", props.enabled());
        body.put("ready", props.ready());
        body.put("allowedEnvironments", props.allowedEnvironments());
        body.put("defaultEnvironment", props.resolvedDefaultEnvironment(deploymentLabel));
        body.put("gitlabProjectUrl", props.enabled() ? props.gitlabProjectUrl() : "");
        body.put("gitHeadSha", "");
        body.put("lastSyncByEnv", Map.of());
        if (!props.enabled()) {
            body.put(
                "configurationIssues",
                List.of(
                    "Config Studio is disabled on this deployment. Enable CONFIG_STUDIO_ENABLED after "
                        + "STREAMMUX_AUTH_ENABLED, Postgres, and vault secrets are in place."
                )
            );
        } else {
            body.put("configurationIssues", props.configurationIssues());
        }
        return body;
    }
}
