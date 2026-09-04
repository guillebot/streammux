package io.github.guillebot.streammux.api.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "streammux.auth.enabled", havingValue = "true")
public class UserRepository {
    private static final String LAST_LOGIN_JOIN = """
              LEFT JOIN (
                  SELECT username, MAX(ts) AS last_login_at
                    FROM auth_audit
                   WHERE event_type = 'LOGIN_SUCCESS'
                   GROUP BY username
              ) ll ON ll.username = u.username
            """;

    private static final String USER_COLUMNS = """
            SELECT u.user_id, u.username, u.email, u.auth_type, u.password_hash, u.enabled,
                   u.failed_attempts, u.locked_until, u.avatar_url, u.entra_roles_overridden,
                   ll.last_login_at
              FROM users u
            """;

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByUsername(String username) {
        try {
            UserAccount base = jdbc.queryForObject(
                USER_COLUMNS + LAST_LOGIN_JOIN + " WHERE u.username = ?",
                baseMapper(),
                username
            );
            return Optional.ofNullable(base).map(this::withRoles);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<UserAccount> findAll() {
        List<UserAccount> base = jdbc.query(
            USER_COLUMNS + LAST_LOGIN_JOIN + " ORDER BY u.username",
            baseMapper()
        );
        return base.stream().map(this::withRoles).toList();
    }

    public int countEnabledAdmins() {
        Integer count = jdbc.queryForObject(
            """
                SELECT COUNT(*)
                  FROM users u
                  JOIN user_roles r ON r.user_id = u.user_id
                 WHERE u.enabled = true AND r.role = 'admin'
                """,
            Integer.class
        );
        return count == null ? 0 : count;
    }

    public long createLocalUser(String username, String email, String bcryptHash, List<String> roles) {
        Long id = jdbc.queryForObject(
            """
                INSERT INTO users (username, email, auth_type, password_hash, enabled, created_at, updated_at)
                VALUES (?, ?, 'LOCAL', ?, true, NOW(), NOW())
                RETURNING user_id
                """,
            Long.class,
            username,
            email,
            bcryptHash
        );
        if (id == null) {
            throw new IllegalStateException("Failed to insert user");
        }
        replaceRoles(id, roles);
        return id;
    }

    public long upsertOidcUser(String username, String email, List<String> roles) {
        Long id = jdbc.queryForObject(
            """
                INSERT INTO users (username, email, auth_type, enabled, created_at, updated_at)
                VALUES (?, ?, 'OIDC', true, NOW(), NOW())
                ON CONFLICT (username) DO UPDATE SET email = EXCLUDED.email, updated_at = NOW()
                RETURNING user_id
                """,
            Long.class,
            username,
            email
        );
        if (id == null) {
            throw new IllegalStateException("Failed to upsert OIDC user");
        }
        if (roles != null) {
            replaceRoles(id, roles);
        }
        return id;
    }

    public void registerLoginFailure(String username, int lockoutThreshold, java.time.Duration lockoutDuration) {
        jdbc.update(
            """
                UPDATE users SET failed_attempts = failed_attempts + 1,
                                 locked_until = CASE WHEN failed_attempts + 1 >= ?
                                                     THEN NOW() + ?::interval ELSE NULL END,
                                 updated_at = NOW()
                 WHERE username = ?
                """,
            lockoutThreshold,
            lockoutDuration.toSeconds() + " seconds",
            username
        );
    }

    public void registerLoginSuccess(String username) {
        jdbc.update(
            "UPDATE users SET failed_attempts = 0, locked_until = NULL, updated_at = NOW() WHERE username = ?",
            username
        );
    }

    public void setEnabled(long userId, boolean enabled) {
        jdbc.update("UPDATE users SET enabled = ?, updated_at = NOW() WHERE user_id = ?", enabled, userId);
    }

    public void updateEmail(long userId, String email) {
        jdbc.update("UPDATE users SET email = ?, updated_at = NOW() WHERE user_id = ?", email, userId);
    }

    public void setEntraRolesOverridden(long userId, boolean overridden) {
        jdbc.update(
            "UPDATE users SET entra_roles_overridden = ?, updated_at = NOW() WHERE user_id = ?",
            overridden,
            userId
        );
    }

    public void clearLockout(long userId) {
        jdbc.update(
            """
                UPDATE users
                   SET failed_attempts = 0, locked_until = NULL, updated_at = NOW()
                 WHERE user_id = ?
                """,
            userId
        );
    }

    public void updatePasswordHash(long userId, String bcryptHash) {
        int n = jdbc.update(
            """
                UPDATE users
                   SET password_hash = ?, failed_attempts = 0, locked_until = NULL, updated_at = NOW()
                 WHERE user_id = ? AND auth_type = 'LOCAL'
                """,
            bcryptHash,
            userId
        );
        if (n == 0) {
            throw new IllegalStateException("user not found or not LOCAL: " + userId);
        }
    }

    public void updateAvatarUrl(long userId, String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return;
        }
        jdbc.update("UPDATE users SET avatar_url = ?, updated_at = NOW() WHERE user_id = ?", avatarUrl, userId);
    }

    public boolean deleteUser(long userId) {
        return jdbc.update("DELETE FROM users WHERE user_id = ?", userId) > 0;
    }

    public int revokeAllSessions(String username) {
        if (username == null || username.isBlank()) {
            return 0;
        }
        return jdbc.update("DELETE FROM spring_session WHERE principal_name = ?", username);
    }

    public int revokeSessionsExcept(String username, String keepSessionId) {
        if (username == null || username.isBlank()) {
            return 0;
        }
        if (keepSessionId == null || keepSessionId.isBlank()) {
            return revokeAllSessions(username);
        }
        return jdbc.update(
            "DELETE FROM spring_session WHERE principal_name = ? AND session_id <> ?",
            username,
            keepSessionId
        );
    }

    public void replaceRoles(long userId, List<String> roles) {
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", userId);
        if (roles == null || roles.isEmpty()) {
            return;
        }
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", userId, role);
        }
    }

    private UserAccount withRoles(UserAccount user) {
        List<String> roles = jdbc.queryForList(
            "SELECT role FROM user_roles WHERE user_id = ? ORDER BY role",
            String.class,
            user.userId()
        );
        return new UserAccount(
            user.userId(),
            user.username(),
            user.email(),
            user.authType(),
            user.passwordHash(),
            user.enabled(),
            user.failedAttempts(),
            user.lockedUntil(),
            roles,
            user.avatarUrl(),
            user.lastLoginAt(),
            user.entraRolesOverridden()
        );
    }

    private RowMapper<UserAccount> baseMapper() {
        return (ResultSet rs, int rowNum) -> new UserAccount(
            rs.getLong("user_id"),
            rs.getString("username"),
            rs.getString("email"),
            UserAccount.AuthType.valueOf(rs.getString("auth_type")),
            rs.getString("password_hash"),
            rs.getBoolean("enabled"),
            rs.getInt("failed_attempts"),
            ts(rs, "locked_until"),
            List.of(),
            rs.getString("avatar_url"),
            ts(rs, "last_login_at"),
            rs.getBoolean("entra_roles_overridden")
        );
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
