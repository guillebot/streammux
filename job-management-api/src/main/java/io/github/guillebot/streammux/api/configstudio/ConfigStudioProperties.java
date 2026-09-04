/*
 * (c) Optimum 2026
 * Guillermo Schimmel
 */

package io.github.guillebot.streammux.api.configstudio;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GitLab-backed Config Studio settings. When {@link #enabled()} is false the
 * submit endpoint returns 503 so operators can still use download + manual MR.
 */
@ConfigurationProperties(prefix = "streammux.config-studio")
public record ConfigStudioProperties(
        boolean enabled,
        String gitlabBaseUrl,
        String gitlabProjectId,
        String gitlabToken,
        String defaultBranch,
        String defaultEnvironment,
        List<String> allowedEnvironments,
        int syncIntervalMinutes,
        boolean requireProdApproval,
        List<String> prodApprovalEnvironments,
        boolean webhookEnabled,
        String webhookSecret
) {

    public ConfigStudioProperties {
        if (gitlabBaseUrl == null || gitlabBaseUrl.isBlank()) {
            gitlabBaseUrl = "https://gitlab.com";
        }
        if (defaultBranch == null || defaultBranch.isBlank()) {
            defaultBranch = "main";
        }
        if (defaultEnvironment != null && !defaultEnvironment.isBlank()) {
            defaultEnvironment = defaultEnvironment.trim().toLowerCase(Locale.ROOT);
        } else {
            defaultEnvironment = null;
        }
        if (allowedEnvironments == null || allowedEnvironments.isEmpty()) {
            allowedEnvironments = List.of("dev", "stage");
        } else {
            allowedEnvironments = List.copyOf(allowedEnvironments);
        }
        if (syncIntervalMinutes < 0) {
            syncIntervalMinutes = 0;
        }
        if (prodApprovalEnvironments == null || prodApprovalEnvironments.isEmpty()) {
            prodApprovalEnvironments = List.of("prod");
        } else {
            prodApprovalEnvironments = List.copyOf(prodApprovalEnvironments);
        }
        if (webhookSecret == null) {
            webhookSecret = "";
        }
    }

    /**
     * Preferred Config Studio environment for this deployment. Explicit
     * {@link #defaultEnvironment()} wins; otherwise maps {@code ONEALARM_DEPLOYMENT_LABEL}
     * (e.g. {@code STAGE} → {@code stage}) when that name is allowed; finally the first
     * entry in {@link #allowedEnvironments()}.
     */
    public String resolvedDefaultEnvironment(String deploymentLabel) {
        if (defaultEnvironment != null && allowedEnvironments.contains(defaultEnvironment)) {
            return defaultEnvironment;
        }
        if (deploymentLabel != null && !deploymentLabel.isBlank()) {
            String fromLabel = deploymentLabel.trim().toLowerCase(Locale.ROOT);
            if (allowedEnvironments.contains(fromLabel)) {
                return fromLabel;
            }
        }
        return allowedEnvironments.isEmpty() ? "stage" : allowedEnvironments.getFirst();
    }

    public boolean ready() {
        return enabled && configurationIssues().isEmpty();
    }

    /**
     * Human-readable gaps when {@link #enabled()} is true but GitLab is not wired.
     */
    public List<String> configurationIssues() {
        if (!enabled) {
            return List.of();
        }
        List<String> issues = new ArrayList<>();
        if (gitlabProjectId == null || gitlabProjectId.isBlank()) {
            issues.add(
                    "Set CONFIG_STUDIO_GITLAB_PROJECT_ID to the streammux-configs GitLab project "
                            + "(path or numeric id).");
        }
        if (gitlabToken == null || gitlabToken.isBlank()) {
            issues.add(
                    "Set CONFIG_STUDIO_GITLAB_TOKEN — a GitLab PAT with api scope on streammux-configs "
                            + "(Ansible: streammux_config_studio_gitlab_token in group vault).");
        }
        return List.copyOf(issues);
    }

    public boolean requiresApproval(String environment) {
        return requireProdApproval
                && environment != null
                && prodApprovalEnvironments.contains(environment.trim().toLowerCase());
    }

    public String gitlabProjectUrl() {
        String base = gitlabBaseUrl.endsWith("/")
                ? gitlabBaseUrl.substring(0, gitlabBaseUrl.length() - 1)
                : gitlabBaseUrl;
        if (gitlabProjectId.matches("\\d+")) {
            return base + "/projects/" + gitlabProjectId;
        }
        return base + "/" + gitlabProjectId;
    }
}
