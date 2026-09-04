package io.github.guillebot.streammux.api.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service("streammuxOidcUserService")
@ConditionalOnProperty(name = "streammux.security.oidc-enabled", havingValue = "true")
public class OidcUserService extends org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService {
    private final UserRepository repo;
    private final SecurityProperties props;

    public OidcUserService(UserRepository repo, SecurityProperties props) {
        this.repo = repo;
        this.props = props;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser delegate = super.loadUser(request);
        String username = pickUsername(delegate);
        String email = delegate.getEmail();
        Optional<UserAccount> existing = repo.findByUsername(username);
        List<String> mappedRoles = mapRolesFromClaim(delegate);
        List<String> sessionRoles = OidcRoleSync.sessionRoles(existing, mappedRoles, props.oidcDefaultRoles());
        if (OidcRoleSync.shouldPersistMappedRoles(existing)) {
            repo.upsertOidcUser(username, email, sessionRoles);
        } else {
            repo.upsertOidcUser(username, email, null);
        }

        Set<GrantedAuthority> authorities = new HashSet<>(delegate.getAuthorities());
        authorities.removeIf(a -> {
            String name = a.getAuthority();
            return "ROLE_VIEWER".equals(name) || "ROLE_OPERATOR".equals(name) || "ROLE_ADMIN".equals(name);
        });
        for (String role : sessionRoles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
        }

        String principalAttr = delegate.getAttributes().containsKey("preferred_username")
            ? "preferred_username"
            : (delegate.getAttributes().containsKey("upn") ? "upn" : "sub");
        return new DefaultOidcUser(authorities, delegate.getIdToken(), delegate.getUserInfo(), principalAttr);
    }

    List<String> mapRolesFromClaim(OidcUser delegate) {
        List<String> claimValues = new ArrayList<>();
        readClaimValues(delegate, "roles", claimValues);
        readClaimValues(delegate, "groups", claimValues);
        Set<String> mapped = new LinkedHashSet<>();
        for (String value : claimValues) {
            String role = props.mapClaimRole(value);
            if (role != null && !role.isBlank()) {
                mapped.add(role);
            }
        }
        return new ArrayList<>(mapped);
    }

    private static void readClaimValues(OidcUser delegate, String claimName, List<String> out) {
        if (delegate.getIdToken() != null) {
            List<String> fromIdToken = delegate.getIdToken().getClaimAsStringList(claimName);
            if (fromIdToken != null) {
                out.addAll(fromIdToken);
            }
        }
        if (delegate.getAttributes().get(claimName) instanceof List<?> attrValues) {
            for (Object value : attrValues) {
                if (value != null) {
                    out.add(value.toString());
                }
            }
        }
    }

    private String pickUsername(OidcUser user) {
        Object pref = user.getAttributes().get("preferred_username");
        if (pref instanceof String s && !s.isBlank()) {
            return s;
        }
        Object upn = user.getAttributes().get("upn");
        if (upn instanceof String s && !s.isBlank()) {
            return s;
        }
        if (user.getEmail() != null) {
            return user.getEmail();
        }
        return user.getName();
    }
}
