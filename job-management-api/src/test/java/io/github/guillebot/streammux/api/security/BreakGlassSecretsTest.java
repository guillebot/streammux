package io.github.guillebot.streammux.api.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BreakGlassSecretsTest {

    @TempDir
    Path tmp;

    @Test
    void generatedPasswordsMeetLengthAndDiffer() {
        String a = BreakGlassSecrets.generatePassword(new SecureRandom(), 24);
        String b = BreakGlassSecrets.generatePassword(new SecureRandom(), 24);
        assertThat(a).hasSize(24);
        assertThat(b).hasSize(24);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void credentialsFileIsOwnerReadWriteOnly() throws Exception {
        Path file = tmp.resolve("creds");
        BreakGlassSecrets.writeCredentialsFile(file, "breakglass", "not-a-real-secret");
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        );
        assertThat(Files.readString(file)).contains("username=breakglass");
    }
}
