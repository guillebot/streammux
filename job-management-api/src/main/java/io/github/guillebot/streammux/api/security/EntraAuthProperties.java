/*
 * (c) Optimum 2026
 * Guillermo Schimmel
 */

package io.github.guillebot.streammux.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Entra-specific auth options. Avatar fetch is opt-in and fail-open; see
 * {@link EntraGraphAvatarService} and AUTH_SSO_PROMPT §7.5.
 */
@ConfigurationProperties(prefix = "streammux.auth.entra")
public record EntraAuthProperties(
        /**
         * When {@code true}, OIDC authorize requests include delegated
         * {@code User.Read} and the API best-effort fetches
         * {@code GET /me/photo/$value} once at login. Default {@code false}.
         */
        boolean fetchAvatar
) {
    public EntraAuthProperties {
        // explicit no-op compact ctor for future defaults
    }
}
