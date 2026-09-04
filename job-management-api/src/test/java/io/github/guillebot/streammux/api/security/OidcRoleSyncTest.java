package io.github.guillebot.streammux.api.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OidcRoleSyncTest {

    @Test
    void firstLoginUsesEntraMappedRoles() {
        List<String> session = OidcRoleSync.sessionRoles(
            Optional.empty(),
            List.of("operator", "viewer"),
            List.of("viewer")
        );
        assertThat(session).containsExactly("operator", "viewer");
        assertThat(OidcRoleSync.shouldPersistMappedRoles(Optional.empty())).isTrue();
    }

    @Test
    void firstLoginFallsBackToDefaultWhenEntraHasNoGroups() {
        List<String> session = OidcRoleSync.sessionRoles(Optional.empty(), List.of(), List.of("viewer"));
        assertThat(session).containsExactly("viewer");
    }

    @Test
    void returningUserWithoutOverrideResyncsEntra() {
        UserAccount existing = oidcUser(false, List.of("viewer"));
        List<String> session = OidcRoleSync.sessionRoles(
            Optional.of(existing),
            List.of("admin"),
            List.of("viewer")
        );
        assertThat(session).containsExactly("admin");
        assertThat(OidcRoleSync.shouldPersistMappedRoles(Optional.of(existing))).isTrue();
    }

    @Test
    void overriddenUserKeepsDatabaseRolesAndDoesNotPersistEntra() {
        UserAccount existing = oidcUser(true, List.of("operator"));
        List<String> session = OidcRoleSync.sessionRoles(
            Optional.of(existing),
            List.of("admin"),
            List.of("viewer")
        );
        assertThat(session).containsExactly("operator");
        assertThat(OidcRoleSync.shouldPersistMappedRoles(Optional.of(existing))).isFalse();
    }

    @Test
    void overriddenUserWithEmptyRolesStaysEmpty() {
        UserAccount existing = oidcUser(true, List.of());
        List<String> session = OidcRoleSync.sessionRoles(
            Optional.of(existing),
            List.of("admin"),
            List.of("viewer")
        );
        assertThat(session).isEmpty();
        assertThat(OidcRoleSync.shouldPersistMappedRoles(Optional.of(existing))).isFalse();
    }

    private static UserAccount oidcUser(boolean overridden, List<String> roles) {
        return new UserAccount(
            1L,
            "alice@example.com",
            "alice@example.com",
            UserAccount.AuthType.OIDC,
            null,
            true,
            0,
            null,
            roles,
            null,
            null,
            overridden
        );
    }
}
