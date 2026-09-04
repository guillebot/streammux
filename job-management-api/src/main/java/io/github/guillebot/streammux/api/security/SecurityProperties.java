/*
 * (c) Optimum 2026
 * Guillermo Schimmel
 */

package io.github.guillebot.streammux.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "streammux.security")
public record SecurityProperties(
        int lockoutThreshold,
        Duration lockoutDuration,
        boolean oidcEnabled,
        boolean localEnabled,
        List<String> oidcDefaultRoles,
        /**
         * Maps an Entra App Role value (the strings emitted in the OIDC
         * {@code roles} claim) to a OneAlarm domain role
         * ({@code viewer}/{@code operator}/{@code admin}). Lookups are
         * case-insensitive: keys are normalized to lower-case here so an
         * app-role value of {@code ADMIN}, {@code Admin}, or {@code admin}
         * all resolve. The default mapping also aliases {@code editor} to
         * {@code operator} for callers whose Entra groups use VIEWER/EDITOR/
         * ADMIN naming.
         */
        Map<String, String> oidcRoleMappings,
        /**
         * Inactive interval applied to a Spring Session row when the user
         * signs in WITHOUT ticking "remember me". Cookie remains a session
         * cookie (cleared on browser close).
         */
        Duration sessionTimeout,
        /**
         * Inactive interval applied — and persistent cookie max-age set —
         * when the user signs in WITH "remember me" checked. Persistent
         * across browser restarts; the row stays valid for this long
         * without activity.
         */
        Duration rememberMeDuration
) {
    /** Request attribute the {@code DefaultCookieSerializer} watches to write a persistent cookie. */
    public static final String REMEMBER_ME_REQUEST_ATTRIBUTE = "onealarm.rememberMe";

    public SecurityProperties {
        if (lockoutThreshold <= 0) lockoutThreshold = 5;
        if (lockoutDuration == null) lockoutDuration = Duration.ofMinutes(15);
        // Local DAO auth stays enabled unless explicitly disabled.
        // OIDC can run alongside this for break-glass access.
        if (oidcDefaultRoles == null) oidcDefaultRoles = List.of("viewer");
        oidcRoleMappings = normalizeRoleMappings(oidcRoleMappings);
        if (sessionTimeout == null || sessionTimeout.isZero() || sessionTimeout.isNegative()) {
            sessionTimeout = Duration.ofHours(8);
        }
        if (rememberMeDuration == null || rememberMeDuration.isZero() || rememberMeDuration.isNegative()) {
            rememberMeDuration = Duration.ofDays(30);
        }
    }

    /**
     * Resolve a single Entra App Role value to a OneAlarm domain role, or
     * {@code null} when the value is unmapped. Case-insensitive.
     */
    public String mapClaimRole(String appRoleValue) {
        if (appRoleValue == null || appRoleValue.isBlank()) return null;
        return oidcRoleMappings.get(appRoleValue.trim().toLowerCase());
    }

    /**
     * Normalize keys (and values) to lower-case so claim lookups are
     * case-insensitive, falling back to the default VIEWER/EDITOR/ADMIN-aware
     * mapping when none is configured.
     */
    private static Map<String, String> normalizeRoleMappings(Map<String, String> configured) {
        if (configured == null || configured.isEmpty()) {
            Map<String, String> defaults = new LinkedHashMap<>();
            defaults.put("viewer", "viewer");
            defaults.put("operator", "operator");
            defaults.put("admin", "admin");
            defaults.put("editor", "operator");
            return Map.copyOf(defaults);
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        configured.forEach((k, v) -> {
            if (k == null || v == null || k.isBlank() || v.isBlank()) return;
            normalized.put(k.trim().toLowerCase(), v.trim().toLowerCase());
        });
        return Map.copyOf(normalized);
    }
}
