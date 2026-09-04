package io.github.guillebot.streammux.api.security;

import java.util.List;
import java.util.Optional;

/**
 * Grafana-style Entra group → role sync. First login (and every later login
 * until an admin overrides) persists mapped groups. After an admin edits
 * roles in the Users page, Entra groups are ignored until sync is resumed.
 */
public final class OidcRoleSync {
    private OidcRoleSync() {}

    public static List<String> sessionRoles(
        Optional<UserAccount> existing,
        List<String> mappedFromEntra,
        List<String> defaultRoles
    ) {
        List<String> fromEntra = (mappedFromEntra == null || mappedFromEntra.isEmpty())
            ? List.copyOf(defaultRoles == null ? List.of() : defaultRoles)
            : List.copyOf(mappedFromEntra);
        if (existing.isPresent()
            && existing.get().entraRolesOverridden()
            && existing.get().roles() != null) {
            return List.copyOf(existing.get().roles());
        }
        return fromEntra;
    }

    public static boolean shouldPersistMappedRoles(Optional<UserAccount> existing) {
        return existing.isEmpty() || !existing.get().entraRolesOverridden();
    }
}
