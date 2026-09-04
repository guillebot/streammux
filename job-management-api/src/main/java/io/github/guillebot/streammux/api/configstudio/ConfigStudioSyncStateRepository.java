package io.github.guillebot.streammux.api.configstudio;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "streammux.config-studio.enabled", havingValue = "true")
public class ConfigStudioSyncStateRepository {
    private final JdbcTemplate jdbc;

    public ConfigStudioSyncStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String environment, String gitSha) {
        jdbc.update(
            """
                INSERT INTO config_studio_sync_state (environment, last_git_sha, synced_at)
                VALUES (?, ?, NOW())
                ON CONFLICT (environment) DO UPDATE
                  SET last_git_sha = EXCLUDED.last_git_sha, synced_at = NOW()
                """,
            environment,
            gitSha
        );
    }

    public Optional<SyncState> find(String environment) {
        var rows = jdbc.query(
            "SELECT environment, last_git_sha, synced_at FROM config_studio_sync_state WHERE environment = ?",
            (rs, rowNum) -> new SyncState(
                rs.getString("environment"),
                rs.getString("last_git_sha"),
                ts(rs.getTimestamp("synced_at"))
            ),
            environment
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Map<String, SyncState> findAllByEnvironment() {
        Map<String, SyncState> out = new LinkedHashMap<>();
        jdbc.query(
            "SELECT environment, last_git_sha, synced_at FROM config_studio_sync_state ORDER BY environment",
            rs -> {
                out.put(
                    rs.getString("environment"),
                    new SyncState(rs.getString("environment"), rs.getString("last_git_sha"), ts(rs.getTimestamp("synced_at")))
                );
            }
        );
        return out;
    }

    private static Instant ts(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record SyncState(String environment, String lastGitSha, Instant syncedAt) {}
}
