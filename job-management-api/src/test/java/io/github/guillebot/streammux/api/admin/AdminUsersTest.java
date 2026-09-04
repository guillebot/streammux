package io.github.guillebot.streammux.api.admin;

import io.github.guillebot.streammux.api.security.UserAccount;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminUsersTest {

    @Test
    void normalizeRolesDedupesAndLowercases() {
        assertThat(AdminUsers.normalizeRoles(List.of("Admin", "VIEWER", "admin")))
            .containsExactly("admin", "viewer");
    }

    @Test
    void normalizeRolesRejectsUnknown() {
        assertThatThrownBy(() -> AdminUsers.normalizeRoles(List.of("superuser")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown_role");
    }

    @Test
    void lastAdminGuardOnlyWhenRemovingAdminCapability() {
        UserAccount last = localAdmin();
        assertThat(AdminUsers.isLastEnabledAdmin(last, 1, true)).isTrue();
        assertThat(AdminUsers.isLastEnabledAdmin(last, 1, false)).isFalse();
        assertThat(AdminUsers.isLastEnabledAdmin(last, 2, true)).isFalse();
    }

    @Test
    void lastAdminGuardIgnoresDisabledAndNonAdmin() {
        UserAccount disabled = new UserAccount(
            1L, "a", null, UserAccount.AuthType.LOCAL, "x", false, 0, null,
            List.of("admin"), null, null, false
        );
        UserAccount viewer = new UserAccount(
            2L, "b", null, UserAccount.AuthType.LOCAL, "x", true, 0, null,
            List.of("viewer"), null, null, false
        );
        assertThat(AdminUsers.isLastEnabledAdmin(disabled, 1, true)).isFalse();
        assertThat(AdminUsers.isLastEnabledAdmin(viewer, 1, true)).isFalse();
    }

    private static UserAccount localAdmin() {
        return new UserAccount(
            1L, "breakglass", null, UserAccount.AuthType.LOCAL, "x", true, 0, null,
            List.of("admin", "operator", "viewer"), null, null, false
        );
    }
}
