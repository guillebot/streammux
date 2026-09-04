package io.github.guillebot.streammux.api.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * CSPRNG password + 0600 credential file for the break-glass local admin.
 * Never log the generated password.
 */
public final class BreakGlassSecrets {
    public static final int PASSWORD_LENGTH = 24;

    /** Ambiguous 0/O/1/l/I omitted. */
    private static final char[] ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    private BreakGlassSecrets() {}

    public static String generatePassword() {
        return generatePassword(new SecureRandom(), PASSWORD_LENGTH);
    }

    static String generatePassword(SecureRandom rng, int length) {
        if (length < 8) {
            throw new IllegalArgumentException("password length");
        }
        char[] buf = new char[length];
        for (int i = 0; i < length; i++) {
            buf[i] = ALPHABET[rng.nextInt(ALPHABET.length)];
        }
        return new String(buf);
    }

    public static void writeCredentialsFile(Path path, String username, String password) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String body = "# Streammux break-glass credentials. Store in Delinea and delete this file.\n"
            + "# Generated at " + Instant.now() + "\n"
            + "username=" + username + "\n"
            + "password=" + password + "\n";
        Files.writeString(path, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            );
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystems (local Windows) still get the file
        }
    }
}
