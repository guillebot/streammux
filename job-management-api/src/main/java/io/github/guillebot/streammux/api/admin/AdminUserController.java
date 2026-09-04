package io.github.guillebot.streammux.api.admin;

import io.github.guillebot.streammux.api.security.AuthAuditService;
import io.github.guillebot.streammux.api.security.UserAccount;
import io.github.guillebot.streammux.api.security.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "streammux.auth.enabled", havingValue = "true")
public class AdminUserController {

    private final UserRepository repo;
    private final PasswordEncoder encoder;
    private final AuthAuditService audit;

    public AdminUserController(UserRepository repo, PasswordEncoder encoder, AuthAuditService audit) {
        this.repo = repo;
        this.encoder = encoder;
        this.audit = audit;
    }

    public record UserView(
        long userId,
        String username,
        String email,
        String authType,
        boolean enabled,
        Instant lockedUntil,
        Instant lastLoginAt,
        List<String> roles,
        boolean entraRolesOverridden
    ) {
        public static UserView of(UserAccount a) {
            return new UserView(
                a.userId(),
                a.username(),
                a.email(),
                a.authType().name(),
                a.enabled(),
                a.lockedUntil(),
                a.lastLoginAt(),
                a.roles(),
                a.entraRolesOverridden()
            );
        }
    }

    public record CreateUserRequest(
        @NotBlank String username,
        String email,
        @NotBlank String password,
        List<String> roles
    ) {}

    public record UpdateRolesRequest(@NotNull List<String> roles) {}

    public record UpdateUserRequest(Boolean enabled, String email) {}

    public record ResetPasswordRequest(@NotBlank String password) {}

    @GetMapping
    public List<UserView> list() {
        return repo.findAll().stream().map(UserView::of).toList();
    }

    @PostMapping
    public ResponseEntity<?> create(
        @RequestBody CreateUserRequest req,
        Authentication auth,
        HttpServletRequest httpReq
    ) {
        String username = req.username() == null ? "" : req.username().trim();
        if (!AdminUsers.USERNAME.matcher(username).matches()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid_username",
                "message", "Username must be 2–64 characters: letters, digits, . _ @ + -"
            ));
        }
        if (req.password() == null || req.password().length() < AdminUsers.MIN_PASSWORD_LENGTH) {
            return weakPassword();
        }
        List<String> roles;
        try {
            roles = AdminUsers.normalizeRoles(req.roles());
        } catch (IllegalArgumentException e) {
            return unknownRole(e);
        }
        if (roles.isEmpty()) {
            roles = List.of("viewer");
        }
        if (repo.findByUsername(username).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "username_exists"));
        }
        String email = req.email() == null || req.email().isBlank() ? null : req.email().trim();
        repo.createLocalUser(username, email, encoder.encode(req.password()), roles);
        audit.record(
            auth.getName(),
            "USER_CREATED",
            httpReq.getRemoteAddr(),
            httpReq.getHeader("User-Agent"),
            Map.of("username", username, "roles", roles)
        );
        return ResponseEntity.ok(repo.findByUsername(username).map(UserView::of).orElseThrow());
    }

    @PutMapping("/{username}/roles")
    public ResponseEntity<?> updateRoles(
        @PathVariable("username") String username,
        @RequestBody UpdateRolesRequest req,
        Authentication auth,
        HttpServletRequest httpReq
    ) {
        var existing = repo.findByUsername(username);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<String> roles;
        try {
            roles = AdminUsers.normalizeRoles(req.roles());
        } catch (IllegalArgumentException e) {
            return unknownRole(e);
        }
        UserAccount user = existing.get();
        boolean removingAdmin = user.roles() != null && user.roles().contains("admin") && !roles.contains("admin");
        if (AdminUsers.isLastEnabledAdmin(user, repo.countEnabledAdmins(), removingAdmin)) {
            return lastAdmin();
        }
        List<String> previousRoles = user.roles();
        repo.replaceRoles(user.userId(), roles);
        if (user.authType() == UserAccount.AuthType.OIDC) {
            repo.setEntraRolesOverridden(user.userId(), true);
        }
        int revoked = repo.revokeAllSessions(username);
        audit.record(
            auth.getName(),
            "USER_ROLES_CHANGED",
            httpReq.getRemoteAddr(),
            httpReq.getHeader("User-Agent"),
            Map.of(
                "username", username,
                "previousRoles", previousRoles == null ? List.of() : previousRoles,
                "newRoles", roles,
                "sessionsRevoked", revoked
            )
        );
        return ResponseEntity.ok(repo.findByUsername(username).map(UserView::of).orElseThrow());
    }

    @PostMapping("/{username}/entra-sync")
    public ResponseEntity<?> resumeEntraSync(
        @PathVariable("username") String username,
        Authentication auth,
        HttpServletRequest httpReq
    ) {
        var existing = repo.findByUsername(username);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (existing.get().authType() != UserAccount.AuthType.OIDC) {
            return ResponseEntity.status(409).body(Map.of(
                "error", "not_oidc_account",
                "message", "Entra sync applies only to OIDC users."
            ));
        }
        repo.setEntraRolesOverridden(existing.get().userId(), false);
        audit.record(
            auth.getName(),
            "USER_ENTRA_SYNC_RESUMED",
            httpReq.getRemoteAddr(),
            httpReq.getHeader("User-Agent"),
            Map.of("username", username)
        );
        return ResponseEntity.ok(repo.findByUsername(username).map(UserView::of).orElseThrow());
    }

    @PatchMapping("/{username}")
    public ResponseEntity<?> patch(
        @PathVariable("username") String username,
        @RequestBody UpdateUserRequest req,
        Authentication auth,
        HttpServletRequest httpReq
    ) {
        var existing = repo.findByUsername(username);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        UserAccount user = existing.get();
        if (req.enabled() != null && !req.enabled()) {
            if (auth.getName() != null && auth.getName().equalsIgnoreCase(username)) {
                return ResponseEntity.status(409).body(Map.of(
                    "error", "cannot_disable_self",
                    "message", "Cannot disable your own account."
                ));
            }
            if (AdminUsers.isLastEnabledAdmin(user, repo.countEnabledAdmins(), true)) {
                return lastAdmin();
            }
            repo.setEnabled(user.userId(), false);
        } else if (req.enabled() != null) {
            repo.setEnabled(user.userId(), true);
        }
        if (req.email() != null) {
            String trimmed = req.email().isBlank() ? null : req.email().trim();
            repo.updateEmail(user.userId(), trimmed);
        }
        Map<String, Object> detail = new HashMap<>();
        detail.put("username", username);
        if (req.enabled() != null) {
            detail.put("previousEnabled", user.enabled());
            detail.put("enabled", req.enabled());
        }
        audit.record(
            auth.getName(),
            "USER_UPDATED",
            httpReq.getRemoteAddr(),
            httpReq.getHeader("User-Agent"),
            detail
        );
        return ResponseEntity.ok(repo.findByUsername(username).map(UserView::of).orElseThrow());
    }

    @PostMapping("/{username}/password")
    public ResponseEntity<?> resetPassword(
        @PathVariable("username") String username,
        @RequestBody ResetPasswordRequest req,
        Authentication auth,
        HttpServletRequest httpReq
    ) {
        var existing = repo.findByUsername(username);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (existing.get().authType() != UserAccount.AuthType.LOCAL) {
            return ResponseEntity.status(409).body(Map.of(
                "error", "not_local_account",
                "message", "Cannot reset the password of an OIDC user."
            ));
        }
        if (req.password() == null || req.password().length() < AdminUsers.MIN_PASSWORD_LENGTH) {
            return weakPassword();
        }
        repo.updatePasswordHash(existing.get().userId(), encoder.encode(req.password()));
        int revoked = repo.revokeAllSessions(username);
        audit.record(
            auth.getName(),
            "USER_PASSWORD_RESET",
            httpReq.getRemoteAddr(),
            httpReq.getHeader("User-Agent"),
            Map.of("username", username, "sessionsRevoked", revoked)
        );
        return ResponseEntity.ok(Map.of("status", "reset", "sessionsRevoked", revoked));
    }

    @PostMapping("/{username}/sessions:revoke")
    public ResponseEntity<?> revokeSessions(
        @PathVariable("username") String username,
        Authentication auth,
        HttpServletRequest httpReq
    ) {
        var existing = repo.findByUsername(username);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        int revoked = repo.revokeAllSessions(username);
        audit.record(
            auth.getName(),
            "USER_SESSIONS_REVOKED",
            httpReq.getRemoteAddr(),
            httpReq.getHeader("User-Agent"),
            Map.of("username", username, "sessionsRevoked", revoked)
        );
        return ResponseEntity.ok(Map.of("status", "revoked", "sessionsRevoked", revoked));
    }

    @PostMapping("/{username}/unlock")
    public ResponseEntity<?> unlock(
        @PathVariable("username") String username,
        Authentication auth,
        HttpServletRequest httpReq
    ) {
        var existing = repo.findByUsername(username);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        repo.clearLockout(existing.get().userId());
        audit.record(
            auth.getName(),
            "USER_UNLOCKED",
            httpReq.getRemoteAddr(),
            httpReq.getHeader("User-Agent"),
            Map.of("username", username)
        );
        return ResponseEntity.ok(repo.findByUsername(username).map(UserView::of).orElseThrow());
    }

    @DeleteMapping("/{username}")
    public ResponseEntity<?> delete(
        @PathVariable("username") String username,
        Authentication auth,
        HttpServletRequest httpReq
    ) {
        var existing = repo.findByUsername(username);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (auth.getName() != null && auth.getName().equalsIgnoreCase(username)) {
            return ResponseEntity.status(409).body(Map.of(
                "error", "cannot_delete_self",
                "message", "Cannot delete your own account."
            ));
        }
        UserAccount user = existing.get();
        if (AdminUsers.isLastEnabledAdmin(user, repo.countEnabledAdmins(), true)) {
            return lastAdmin();
        }
        audit.record(
            auth.getName(),
            "USER_DELETED",
            httpReq.getRemoteAddr(),
            httpReq.getHeader("User-Agent"),
            Map.of("username", username)
        );
        repo.deleteUser(user.userId());
        repo.revokeAllSessions(username);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    private static ResponseEntity<Map<String, String>> lastAdmin() {
        return ResponseEntity.status(409).body(Map.of(
            "error", "last_admin",
            "message", "Cannot remove the last enabled admin."
        ));
    }

    private static ResponseEntity<Map<String, String>> weakPassword() {
        return ResponseEntity.badRequest().body(Map.of(
            "error", "weak_password",
            "message", "Password must be at least " + AdminUsers.MIN_PASSWORD_LENGTH + " characters."
        ));
    }

    private static ResponseEntity<Map<String, String>> unknownRole(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
            "error", "unknown_role",
            "message", e.getMessage()
        ));
    }
}
