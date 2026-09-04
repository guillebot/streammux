package io.github.guillebot.streammux.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@ConditionalOnProperty(name = "streammux.auth.enabled", havingValue = "true")
public class AuthAuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuthAuditService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void record(String username, String eventType, String ip, String userAgent, Map<String, Object> detail) {
        try {
            String json = detail == null ? "{}" : objectMapper.writeValueAsString(detail);
            jdbc.update(
                "INSERT INTO auth_audit (username, event_type, ip, user_agent, detail) VALUES (?, ?, ?, ?, ?::jsonb)",
                username,
                eventType,
                ip,
                userAgent,
                json
            );
        } catch (Exception ignored) {
            // audit must never block auth
        }
    }
}
