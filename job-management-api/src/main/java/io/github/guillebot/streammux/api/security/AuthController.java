package io.github.guillebot.streammux.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "streammux.auth.enabled", havingValue = "true")
public class AuthController {
    public enum AuthType {
        LOCAL,
        OIDC,
        ANONYMOUS
    }

    private final SecurityProperties securityProperties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthAuditService audit;
    private final String deploymentLabel;
    private final String deploymentTone;

    public AuthController(
        SecurityProperties securityProperties,
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        AuthAuditService audit,
        @Value("${STREAMMUX_DEPLOYMENT_LABEL:}") String deploymentLabel,
        @Value("${STREAMMUX_DEPLOYMENT_TONE:info}") String deploymentTone
    ) {
        this.securityProperties = securityProperties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.deploymentLabel = deploymentLabel == null ? "" : deploymentLabel.trim();
        this.deploymentTone = deploymentTone == null ? "info" : deploymentTone.trim().toLowerCase();
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication auth) {
        if (auth == null) {
            return ResponseEntity.status(401).build();
        }
        List<String> roles = auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .map(s -> s.startsWith("ROLE_") ? s.substring(5).toLowerCase() : s)
            .filter(r -> !"none".equals(r))
            .toList();
        AuthType authType = auth.getPrincipal() instanceof OidcUser ? AuthType.OIDC : AuthType.LOCAL;
        Map<String, Object> body = new HashMap<>();
        body.put("username", auth.getName());
        body.put("roles", roles);
        body.put("role", roles.isEmpty() ? "viewer" : roles.get(roles.size() - 1));
        body.put("authType", authType.name());
        body.put(
            "auth",
            Map.of(
                "oidc", Map.of("enabled", securityProperties.oidcEnabled()),
                "local", Map.of("enabled", securityProperties.localEnabled()),
                "deployment", deploymentConfig()
            )
        );
        userRepository.findByUsername(auth.getName())
            .map(UserAccount::avatarUrl)
            .filter(url -> url != null && !url.isBlank())
            .ifPresent(url -> body.put("avatarUrl", url));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
            "oidc", Map.of("enabled", securityProperties.oidcEnabled()),
            "local", Map.of("enabled", securityProperties.localEnabled()),
            "deployment", deploymentConfig()
        );
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    @PostMapping("/password")
    public ResponseEntity<?> changePassword(
        @RequestBody ChangePasswordRequest req,
        Authentication auth,
        HttpServletRequest httpReq
    ) {
        if (auth == null) {
            return ResponseEntity.status(401).build();
        }
        if (auth.getPrincipal() instanceof OidcUser) {
            return ResponseEntity.status(409).body(Map.of("error", "oidc_account"));
        }
        String username = auth.getName();
        var account = userRepository.findByUsername(username).orElse(null);
        if (account == null || account.authType() != UserAccount.AuthType.LOCAL) {
            return ResponseEntity.status(409).body(Map.of("error", "not_local_account"));
        }
        if (!passwordEncoder.matches(
            req.currentPassword(),
            account.passwordHash() == null ? "" : account.passwordHash()
        )) {
            return ResponseEntity.status(400).body(Map.of("error", "current_password_invalid"));
        }
        userRepository.updatePasswordHash(account.userId(), passwordEncoder.encode(req.newPassword()));
        HttpSession current = httpReq.getSession(false);
        String keepSessionId = current == null ? null : current.getId();
        int revoked = userRepository.revokeSessionsExcept(username, keepSessionId);
        audit.record(username, "PASSWORD_CHANGED", httpReq.getRemoteAddr(), httpReq.getHeader("User-Agent"), Map.of("sessionsRevoked", revoked));
        return ResponseEntity.ok(Map.of("status", "changed", "sessionsRevoked", revoked));
    }

    private Map<String, Object> deploymentConfig() {
        return Map.of(
            "label", deploymentLabel,
            "tone", switch (deploymentTone) {
                case "warn", "warning" -> "warn";
                case "danger", "error", "prod" -> "danger";
                case "ok", "success" -> "ok";
                case "muted" -> "muted";
                default -> "info";
            }
        );
    }
}
