package io.github.guillebot.streammux.api.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "streammux.auth.enabled", havingValue = "true")
public class BootstrapAdminSeeder implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(BootstrapAdminSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthAuditService audit;
    private final String username;
    private final String password;
    private final String fallbackUsername;

    public BootstrapAdminSeeder(
        UserRepository users,
        PasswordEncoder encoder,
        AuthAuditService audit,
        @Value("${STREAMMUX_BOOTSTRAP_ADMIN_USERNAME:}") String username,
        @Value("${STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD:}") String password,
        @Value("${STREAMMUX_BOOTSTRAP_ADMIN_FALLBACK_USERNAME:}") String fallbackUsername
    ) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
        this.username = username;
        this.password = password;
        this.fallbackUsername = fallbackUsername;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return;
        }
        String targetUsername = resolveTargetUsername();
        if (targetUsername == null) {
            return;
        }
        var existing = users.findByUsername(targetUsername);
        if (existing.isPresent()) {
            UserAccount user = existing.get();
            if (user.authType() != UserAccount.AuthType.LOCAL) {
                LOG.warn("Bootstrap admin username '{}' exists as {}; cannot set local password from env", targetUsername, user.authType());
                return;
            }
            users.updatePasswordHash(user.userId(), encoder.encode(password));
            users.setEnabled(user.userId(), true);
            users.replaceRoles(user.userId(), List.of("admin", "operator", "viewer"));
            int revoked = users.revokeAllSessions(targetUsername);
            audit.record(targetUsername, "BOOTSTRAP_ADMIN_RESET", null, null, Map.of("sessionsRevoked", revoked));
            LOG.warn("Reset bootstrap admin '{}'. Remove STREAMMUX_BOOTSTRAP_ADMIN_* after first login.", targetUsername);
            return;
        }
        users.createLocalUser(targetUsername, targetUsername, encoder.encode(password), List.of("admin", "operator", "viewer"));
        audit.record(targetUsername, "BOOTSTRAP_ADMIN_CREATED", null, null, Map.of("source", "env"));
        LOG.warn("Created bootstrap admin '{}'. Remove STREAMMUX_BOOTSTRAP_ADMIN_* after first login.", targetUsername);
    }

    private String resolveTargetUsername() {
        var existing = users.findByUsername(username);
        if (existing.isEmpty()) {
            return username;
        }
        if (existing.get().authType() == UserAccount.AuthType.LOCAL) {
            return username;
        }
        if (fallbackUsername == null || fallbackUsername.isBlank()) {
            LOG.warn(
                "Bootstrap admin username '{}' exists as OIDC; set STREAMMUX_BOOTSTRAP_ADMIN_FALLBACK_USERNAME for break-glass.",
                username
            );
            return null;
        }
        return fallbackUsername.trim();
    }
}
