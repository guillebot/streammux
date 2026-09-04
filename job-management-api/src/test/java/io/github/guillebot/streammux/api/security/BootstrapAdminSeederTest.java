package io.github.guillebot.streammux.api.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BootstrapAdminSeederTest {

    @TempDir
    Path tmp;

    @Test
    void generatesPasswordOnceWhenEnvUnsetAndUserMissing() throws Exception {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuthAuditService audit = mock(AuthAuditService.class);
        when(users.findByUsername("breakglass")).thenReturn(Optional.empty());
        when(encoder.encode(anyString())).thenReturn("hash");

        Path file = tmp.resolve("breakglass.credentials");
        seeder(users, encoder, audit, "", file.toString()).run(new DefaultApplicationArguments());

        verify(users).createLocalUser(eq("breakglass"), eq("breakglass"), eq("hash"), eq(List.of("admin", "operator", "viewer")));
        verify(users, never()).updatePasswordHash(org.mockito.ArgumentMatchers.anyLong(), anyString());
        assertThat(Files.exists(file)).isTrue();
        String body = Files.readString(file);
        assertThat(body).contains("username=breakglass");
        assertThat(body).contains("password=");
        assertThat(body).doesNotContain("hash");
    }

    @Test
    void doesNotRotateWhenUserExistsAndEnvPasswordBlank() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuthAuditService audit = mock(AuthAuditService.class);
        UserAccount existing = new UserAccount(
            9L, "breakglass", "breakglass", UserAccount.AuthType.LOCAL, "old", true, 0, null,
            List.of("admin"), null, null, false
        );
        when(users.findByUsername("breakglass")).thenReturn(Optional.of(existing));

        seeder(users, encoder, audit, "", tmp.resolve("unused").toString()).run(new DefaultApplicationArguments());

        verify(users, never()).createLocalUser(anyString(), any(), anyString(), anyList());
        verify(users, never()).updatePasswordHash(org.mockito.ArgumentMatchers.anyLong(), anyString());
        verify(encoder, never()).encode(anyString());
    }

    @Test
    void envPasswordResetsExistingLocalAdmin() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuthAuditService audit = mock(AuthAuditService.class);
        UserAccount existing = new UserAccount(
            9L, "breakglass", "breakglass", UserAccount.AuthType.LOCAL, "old", true, 0, null,
            List.of("admin"), null, null, false
        );
        when(users.findByUsername("breakglass")).thenReturn(Optional.of(existing));
        when(encoder.encode("from-env-secret")).thenReturn("new-hash");

        new BootstrapAdminSeeder(
            users, encoder, audit, "breakglass", "from-env-secret", "", tmp.resolve("unused").toString()
        ).run(new DefaultApplicationArguments());

        verify(users).updatePasswordHash(9L, "new-hash");
        verify(users, never()).createLocalUser(anyString(), any(), anyString(), anyList());
    }

    @Test
    void failClosedWhenGeneratedPasswordFileCannotBeWritten() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuthAuditService audit = mock(AuthAuditService.class);
        when(users.findByUsername("breakglass")).thenReturn(Optional.empty());

        Path notADir = tmp.resolve("blocked");
        try {
            Files.writeString(notADir, "nope");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Path impossible = notADir.resolve("child").resolve("creds");

        assertThatThrownBy(() ->
            seeder(users, encoder, audit, "", impossible.toString()).run(new DefaultApplicationArguments())
        ).isInstanceOf(IllegalStateException.class);

        verify(users, never()).createLocalUser(anyString(), any(), anyString(), anyList());
    }

    private static BootstrapAdminSeeder seeder(
        UserRepository users,
        PasswordEncoder encoder,
        AuthAuditService audit,
        String envPassword,
        String file
    ) {
        return new BootstrapAdminSeeder(users, encoder, audit, "breakglass", envPassword, "", file);
    }
}
