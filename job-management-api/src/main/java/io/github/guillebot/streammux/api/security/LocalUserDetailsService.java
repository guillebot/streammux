/*
 * (c) Optimum 2026
 * Guillermo Schimmel
 */

package io.github.guillebot.streammux.api.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads local users from Postgres for form-based login. OIDC users are
 * provisioned by {@link OidcUserService} on first SSO sign-in.
 */
@Service
@ConditionalOnProperty(name = "streammux.auth.enabled", havingValue = "true")
public class LocalUserDetailsService implements UserDetailsService {

    private final UserRepository repo;

    public LocalUserDetailsService(UserRepository repo) {
        this.repo = repo;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = repo.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        if (account.authType() != UserAccount.AuthType.LOCAL) {
            throw new UsernameNotFoundException("User is OIDC-only: " + username);
        }

        var authorities = account.roles().stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()))
                .toList();

        // No implicit ROLE_VIEWER fallback: an empty role list means a user
        // explicitly stripped of access (e.g. admin disabled them via DELETE
        // /api/admin/users/{username}). Login still succeeds (so audit/2FA
        // flows can complete) but every protected endpoint returns 403 because
        // there are no granted authorities.
        return User.withUsername(account.username())
                .password(account.passwordHash() == null ? "" : account.passwordHash())
                .disabled(!account.enabled())
                .accountLocked(account.isLocked())
                .authorities(authorities.isEmpty()
                        ? List.<SimpleGrantedAuthority>of(new SimpleGrantedAuthority("ROLE_NONE"))
                        : authorities)
                .build();
    }
}
