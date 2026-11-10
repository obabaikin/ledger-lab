package com.ledgerlab.control.entry;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InviteRepository {

    private final JdbcClient jdbc;

    public InviteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public @Nullable Invite find(String token) {
        return jdbc.sql("SELECT token, quota, active AND expires_at > now() AS valid FROM invite_tokens WHERE token = :token")
            .param("token", token)
            .query((rs, rowNum) -> new Invite(rs.getString("token"), rs.getInt("quota"), rs.getBoolean("valid")))
            .optional()
            .orElse(null);
    }

    public void recordVisit(String token) {
        jdbc.sql(
            """
            INSERT INTO invite_visits (token, first_seen, last_seen, count) VALUES (:token, now(), now(), 1)
            ON CONFLICT (token) DO UPDATE SET last_seen = now(), count = invite_visits.count + 1
            """)
            .param("token", token)
            .update();
    }

    public UUID createSession(String token) {
        var sessionId = UUID.randomUUID();
        jdbc.sql("INSERT INTO sessions (session_id, token) VALUES (:sessionId, :token)")
            .param("sessionId", sessionId)
            .param("token", token)
            .update();
        return sessionId;
    }

    public @Nullable Session findSession(UUID sessionId) {
        return jdbc.sql("SELECT session_id, token FROM sessions WHERE session_id = :sessionId")
            .param("sessionId", sessionId)
            .query((rs, rowNum) -> new Session(rs.getObject("session_id", UUID.class), rs.getString("token")))
            .optional()
            .orElse(null);
    }
}
