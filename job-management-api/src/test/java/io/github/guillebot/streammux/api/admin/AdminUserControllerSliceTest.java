package io.github.guillebot.streammux.api.admin;

import io.github.guillebot.streammux.api.security.AuthAuditService;
import io.github.guillebot.streammux.api.security.LocalUserDetailsService;
import io.github.guillebot.streammux.api.security.OidcUserService;
import io.github.guillebot.streammux.api.security.SessionSecurityConfig;
import io.github.guillebot.streammux.api.security.UserAccount;
import io.github.guillebot.streammux.api.security.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = AdminUserController.class,
    excludeAutoConfiguration = {
        DataSourceAutoConfiguration.class,
        SessionAutoConfiguration.class
    }
)
@Import(SessionSecurityConfig.class)
@TestPropertySource(properties = {
    "streammux.auth.enabled=true",
    "streammux.security.oidc-enabled=false",
    "streammux.security.local-enabled=true"
})
class AdminUserControllerSliceTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    UserRepository repo;

    @MockBean
    LocalUserDetailsService userDetailsService;

    @MockBean
    OidcUserService oidcUserService;

    @MockBean
    AuthAuditService audit;

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotListUsers() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminListsUsers() throws Exception {
        when(repo.findAll()).thenReturn(List.of(local("alice", List.of("admin"))));
        mvc.perform(get("/api/admin/users"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].username").value("alice"))
            .andExpect(jsonPath("$[0].entraRolesOverridden").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void cannotDemoteLastAdmin() throws Exception {
        when(repo.findByUsername("alice")).thenReturn(Optional.of(local("alice", List.of("admin"))));
        when(repo.countEnabledAdmins()).thenReturn(1);
        mvc.perform(put("/api/admin/users/alice/roles")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"viewer\"]}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("last_admin"));
        verify(repo, never()).replaceRoles(anyLong(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void roleOverrideMarksOidcUserAndRevokesSessions() throws Exception {
        UserAccount oidc = new UserAccount(
            4L, "bob@x", "bob@x", UserAccount.AuthType.OIDC, null, true, 0, null,
            List.of("viewer"), null, null, false
        );
        UserAccount updated = new UserAccount(
            4L, "bob@x", "bob@x", UserAccount.AuthType.OIDC, null, true, 0, null,
            List.of("admin"), null, null, true
        );
        when(repo.findByUsername("bob@x")).thenReturn(Optional.of(oidc), Optional.of(updated));
        when(repo.countEnabledAdmins()).thenReturn(2);
        when(repo.revokeAllSessions("bob@x")).thenReturn(1);

        mvc.perform(put("/api/admin/users/bob@x/roles")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"admin\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entraRolesOverridden").value(true));

        verify(repo).replaceRoles(4L, List.of("admin"));
        verify(repo).setEntraRolesOverridden(4L, true);
        verify(repo).revokeAllSessions("bob@x");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void resumeEntraSyncClearsOverride() throws Exception {
        UserAccount oidc = new UserAccount(
            4L, "bob@x", "bob@x", UserAccount.AuthType.OIDC, null, true, 0, null,
            List.of("admin"), null, null, true
        );
        UserAccount cleared = new UserAccount(
            4L, "bob@x", "bob@x", UserAccount.AuthType.OIDC, null, true, 0, null,
            List.of("admin"), null, null, false
        );
        when(repo.findByUsername("bob@x")).thenReturn(Optional.of(oidc), Optional.of(cleared));

        mvc.perform(post("/api/admin/users/bob@x/entra-sync").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entraRolesOverridden").value(false));
        verify(repo).setEntraRolesOverridden(4L, false);
    }

    @Test
    @WithMockUser(username = "alice", roles = "ADMIN")
    void cannotDeleteSelf() throws Exception {
        when(repo.findByUsername("alice")).thenReturn(Optional.of(local("alice", List.of("admin"))));
        mvc.perform(delete("/api/admin/users/alice").with(csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("cannot_delete_self"));
        verify(repo, never()).deleteUser(anyLong());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createLocalUser() throws Exception {
        when(repo.findByUsername("carol")).thenReturn(
            Optional.empty(),
            Optional.of(local("carol", List.of("viewer")))
        );
        mvc.perform(post("/api/admin/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"carol\",\"password\":\"long-enough\",\"roles\":[\"viewer\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("carol"));
        verify(repo).createLocalUser(eq("carol"), eq(null), anyString(), eq(List.of("viewer")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void passwordResetRevokesSessions() throws Exception {
        when(repo.findByUsername("dave")).thenReturn(Optional.of(local("dave", List.of("operator"))));
        when(repo.revokeAllSessions("dave")).thenReturn(2);
        mvc.perform(post("/api/admin/users/dave/password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"new-strong-password\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionsRevoked").value(2));
        verify(repo).updatePasswordHash(eq(1L), anyString());
        verify(repo).revokeAllSessions("dave");
    }

    private static UserAccount local(String username, List<String> roles) {
        return new UserAccount(
            1L, username, null, UserAccount.AuthType.LOCAL, "hash", true, 0, null,
            roles, null, null, false
        );
    }
}
