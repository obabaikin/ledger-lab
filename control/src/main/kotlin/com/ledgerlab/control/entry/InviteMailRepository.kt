package com.ledgerlab.control.entry

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

enum class MailKind { LINK, LIMIT }

data class AddressMails(val links: Int, val limits: Int, val oldestLink: Instant?)

data class DailyMails(val count: Int, val oldest: Instant?)

data class Mail(
    val mailId: Long,
    val email: String?,
    val token: String,
    val kind: MailKind,
    val windowFreesAt: Instant?,
    val attempts: Int,
)

/**
 * Times come from clock_timestamp(), taken after the lock, as for the run queue: now() is the transaction start,
 * which may lie before a wait for the lock.
 */
@Repository
class InviteMailRepository(private val jdbc: JdbcClient) {

    fun lock() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))")
            .param("name", INVITE_REQUESTS_LOCK)
            .query { _, _ -> }
            .single()
    }

    fun now(): Instant = jdbc.sql("SELECT clock_timestamp()").query(Timestamp::class.java).single().toInstant()

    fun dailyMails(since: Instant): DailyMails =
        jdbc.sql("SELECT count(*) AS count, min(requested_at) AS oldest FROM invite_mails WHERE requested_at > :since")
            .param("since", Timestamp.from(since))
            .query { rs, _ -> DailyMails(rs.getInt("count"), rs.instantOrNull("oldest")) }
            .single()

    fun addressMails(email: String, since: Instant): AddressMails =
        jdbc.sql(
            """
            SELECT count(*) FILTER (WHERE kind = 'LINK') AS links,
                   count(*) FILTER (WHERE kind = 'LIMIT') AS limits,
                   min(requested_at) FILTER (WHERE kind = 'LINK') AS oldest_link
            FROM invite_mails WHERE email = :email AND requested_at > :since
            """,
        )
            .param("email", email)
            .param("since", Timestamp.from(since))
            .query { rs, _ -> AddressMails(rs.getInt("links"), rs.getInt("limits"), rs.instantOrNull("oldest_link")) }
            .single()

    fun inviteOf(email: String): Invite? =
        jdbc.sql(
            """
            SELECT t.token, t.quota, t.active AND t.expires_at > clock_timestamp() AS valid
            FROM invite_emails e JOIN invite_tokens t ON t.token = e.token WHERE e.email = :email
            """,
        )
            .param("email", email)
            .query { rs, _ -> Invite(rs.getString("token"), rs.getInt("quota"), rs.getBoolean("valid")) }
            .optional()
            .orElse(null)

    fun createInvite(token: String, at: Instant, lifetime: Duration, quota: Int) {
        jdbc.sql("INSERT INTO invite_tokens (token, created_at, expires_at, quota) VALUES (:token, :at, :expiresAt, :quota)")
            .param("token", token)
            .param("at", Timestamp.from(at))
            .param("expiresAt", Timestamp.from(at + lifetime))
            .param("quota", quota)
            .update()
    }

    fun extendInvite(token: String, at: Instant, lifetime: Duration) {
        jdbc.sql("UPDATE invite_tokens SET expires_at = :expiresAt WHERE token = :token")
            .param("token", token)
            .param("expiresAt", Timestamp.from(at + lifetime))
            .update()
    }

    fun setAddressInvite(email: String, token: String, at: Instant) {
        jdbc.sql(
            """
            INSERT INTO invite_emails (email, token, created_at) VALUES (:email, :token, :at)
            ON CONFLICT (email) DO UPDATE SET token = EXCLUDED.token
            """,
        )
            .param("email", email)
            .param("token", token)
            .param("at", Timestamp.from(at))
            .update()
    }

    fun recordRequest(email: String, at: Instant) {
        jdbc.sql("INSERT INTO invite_requests (email, requested_at) VALUES (:email, :at)")
            .param("email", email)
            .param("at", Timestamp.from(at))
            .update()
    }

    fun insertMail(email: String, token: String, kind: MailKind, at: Instant, windowFreesAt: Instant?) {
        jdbc.sql(
            """
            INSERT INTO invite_mails (email, token, kind, requested_at, window_frees_at, next_attempt_at)
            VALUES (:email, :token, :kind, :at, :windowFreesAt, :at)
            """,
        )
            .param("email", email)
            .param("token", token)
            .param("kind", kind.name)
            .param("at", Timestamp.from(at))
            .param("windowFreesAt", windowFreesAt?.let(Timestamp::from))
            .update()
    }

    fun claimDue(limit: Int): List<Mail> =
        jdbc.sql(
            """
            SELECT mail_id, email, token, kind, window_frees_at, attempts FROM invite_mails
            WHERE sent_at IS NULL AND failed_at IS NULL AND next_attempt_at <= clock_timestamp()
            ORDER BY next_attempt_at, mail_id LIMIT :limit FOR UPDATE SKIP LOCKED
            """,
        )
            .param("limit", limit)
            .query { rs, _ ->
                Mail(
                    mailId = rs.getLong("mail_id"),
                    email = rs.getString("email"),
                    token = rs.getString("token"),
                    kind = MailKind.valueOf(rs.getString("kind")),
                    windowFreesAt = rs.instantOrNull("window_frees_at"),
                    attempts = rs.getInt("attempts"),
                )
            }
            .list()

    fun markSent(mailId: Long) {
        jdbc.sql("UPDATE invite_mails SET sent_at = clock_timestamp(), attempts = attempts + 1 WHERE mail_id = :mailId")
            .param("mailId", mailId)
            .update()
    }

    fun markFailed(mailId: Long) {
        jdbc.sql("UPDATE invite_mails SET failed_at = clock_timestamp(), attempts = attempts + 1 WHERE mail_id = :mailId")
            .param("mailId", mailId)
            .update()
    }

    fun retryLater(mailId: Long, pause: Duration) {
        jdbc.sql(
            """
            UPDATE invite_mails SET attempts = attempts + 1,
                next_attempt_at = clock_timestamp() + CAST(:millis AS bigint) * interval '1 millisecond'
            WHERE mail_id = :mailId
            """,
        )
            .param("millis", pause.toMillis())
            .param("mailId", mailId)
            .update()
    }

    /** Use is the latest request from the form or the latest visit by the address's link. */
    fun deleteUnusedSince(before: Instant): Int =
        jdbc.sql(
            """
            DELETE FROM invite_emails e
            WHERE greatest(
                e.created_at,
                (SELECT max(r.requested_at) FROM invite_requests r WHERE r.email = e.email),
                (SELECT v.last_seen FROM invite_visits v WHERE v.token = e.token)
            ) < :before
            """,
        )
            .param("before", Timestamp.from(before))
            .update()

    private fun ResultSet.instantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()

    private companion object {
        const val INVITE_REQUESTS_LOCK = "control.invite-requests"
    }
}
