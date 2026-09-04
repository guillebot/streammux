package io.github.guillebot.streammux.api.security;

import java.time.Instant;
import java.util.List;

public record UserAccount(
    long userId,
    String username,
    String email,
    AuthType authType,
    String passwordHash,
    boolean enabled,
    int failedAttempts,
    Instant lockedUntil,
    List<String> roles,
    String avatarUrl,
    Instant lastLoginAt,
    boolean entraRolesOverridden
) {
    public enum AuthType {
        LOCAL,
        OIDC
    }

    public boolean isLocked() {
        return lockedUntil != null && Instant.now().isBefore(lockedUntil);
    }
}
