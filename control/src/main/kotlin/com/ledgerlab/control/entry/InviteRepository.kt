package com.ledgerlab.control.entry

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

data class Invite(val token: String, val quota: Int, val valid: Boolean)

data class Session(val sessionId: UUID, val token: String)

@Repository
class InviteRepository(private val jdbc: JdbcClient) {

    fun find(token: String): Invite? =
        jdbc.sql("SELECT token, quota, active AND expires_at > now() AS valid FROM invite_tokens WHERE token = :token")
            .param("token", token)
            .query { rs, _ -> Invite(rs.getString("token"), rs.getInt("quota"), rs.getBoolean("valid")) }
            .optional()
            .orElse(null)

    fun recordVisit(token: String) {
        jdbc.sql(
            """
            INSERT INTO invite_visits (token, first_seen, last_seen, count) VALUES (:token, now(), now(), 1)
            ON CONFLICT (token) DO UPDATE SET last_seen = now(), count = invite_visits.count + 1
            """,
        )
            .param("token", token)
            .update()
    }

    fun createSession(token: String): UUID {
        val sessionId = UUID.randomUUID()
        jdbc.sql("INSERT INTO sessions (session_id, token) VALUES (:sessionId, :token)")
            .param("sessionId", sessionId)
            .param("token", token)
            .update()
        return sessionId
    }

    fun findSession(sessionId: UUID): Session? =
        jdbc.sql("SELECT session_id, token FROM sessions WHERE session_id = :sessionId")
            .param("sessionId", sessionId)
            .query { rs, _ -> Session(rs.getObject("session_id", UUID::class.java), rs.getString("token")) }
            .optional()
            .orElse(null)
}
