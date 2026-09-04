package io.github.guillebot.streammux.api.admin;

import io.github.guillebot.streammux.api.security.UserAccount;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class AdminUsers {
    public static final Set<String> ALLOWED_ROLES = Set.of("viewer", "operator", "admin");
    public static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._@+-]{2,64}$");
    public static final int MIN_PASSWORD_LENGTH = 8;

    private AdminUsers() {}

    public static List<String> normalizeRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : roles) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String role = raw.trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_ROLES.contains(role)) {
                throw new IllegalArgumentException("unknown_role:" + role);
            }
            out.add(role);
        }
        return new ArrayList<>(out);
    }

    public static boolean isLastEnabledAdmin(UserAccount target, int enabledAdminCount, boolean removingAdminCapability) {
        if (!removingAdminCapability) {
            return false;
        }
        if (target == null || !target.enabled()) {
            return false;
        }
        if (target.roles() == null || !target.roles().contains("admin")) {
            return false;
        }
        return enabledAdminCount <= 1;
    }
}
