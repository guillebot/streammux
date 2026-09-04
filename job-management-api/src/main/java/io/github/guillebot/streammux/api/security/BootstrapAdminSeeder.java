package io.github.guillebot.streammux.api.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "streammux.auth.enabled", havingValue = "true")
public class BootstrapAdminSeeder implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(BootstrapAdminSeeder.class);
    static final String DEFAULT_USERNAME = "breakglass";
    static final List<String> ADMIN_ROLES = List.of("admin", "operator", "viewer");

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthAuditService audit;
    private final String username;
    private final String password;
    private final String fallbackUsername;
    private final Path passwordFile;

    public BootstrapAdminSeeder(
        UserRepository users,
        PasswordEncoder encoder,
        AuthAuditService audit,
        @Value("${STREAMMUX_BOOTSTRAP_ADMIN_USERNAME:breakglass}") String username,
        @Value("${STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD:}") String password,
        @Value("${STREAMMUX_BOOTSTRAP_ADMIN_FALLBACK_USERNAME:}") String fallbackUsername,
        @Value("${STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD_FILE:}") String passwordFile
    ) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
        this.username = username == null || username.isBlank() ? DEFAULT_USERNAME : username.trim();
        this.password = password == null ? "" : password;
        this.fallbackUsername = fallbackUsername;
        this.passwordFile = (passwordFile == null || passwordFile.isBlank())
            ? Path.of(System.getProperty("java.io.tmpdir"), "streammux-breakglass.credentials")
            : Path.of(passwordFile);
    }

    @Override
    public void run(ApplicationArguments args) {
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
            if (password.isBlank()) {
                return;
            }
            users.updatePasswordHash(user.userId(), encoder.encode(password));
            users.setEnabled(user.userId(), true);
            users.replaceRoles(user.userId(), ADMIN_ROLES);
            int revoked = users.revokeAllSessions(targetUsername);
            audit.record(targetUsername, "BOOTSTRAP_ADMIN_RESET", null, null, Map.of("sessionsRevoked", revoked));
            LOG.warn("Reset bootstrap admin '{}'. Remove STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD after first login.", targetUsername);
            return;
        }

        String secret = password;
        String source = "env";
        if (secret.isBlank()) {
            secret = BreakGlassSecrets.generatePassword();
            try {
                BreakGlassSecrets.writeCredentialsFile(passwordFile, targetUsername, secret);
            } catch (Exception e) {
                LOG.error(
                    "Failed to write break-glass credentials file '{}'. Set STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD or fix the path.",
                    passwordFile
                );
                throw new IllegalStateException("Cannot persist break-glass credentials to " + passwordFile, e);
            }
            source = "generated";
        }
        users.createLocalUser(targetUsername, targetUsername, encoder.encode(secret), ADMIN_ROLES);
        audit.record(targetUsername, "BOOTSTRAP_ADMIN_CREATED", null, null, Map.of("source", source));
        if ("generated".equals(source)) {
            LOG.warn(
                "Created break-glass admin '{}'. Password written to {} (mode 0600). Store in Delinea and delete the file.",
                targetUsername,
                passwordFile
            );
        } else {
            LOG.warn("Created bootstrap admin '{}'. Remove STREAMMUX_BOOTSTRAP_ADMIN_PASSWORD after first login.", targetUsername);
        }
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
