package com.ledgerlab.control.entry;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Times come from clock_timestamp(), taken after the lock, as for the run queue: now() is the transaction start,
 * which may lie before a wait for the lock.
 */
@Repository
public class InviteMailRepository {

    private static final String INVITE_REQUESTS_LOCK = "control.invite-requests";

    private final JdbcClient jdbc;

    public InviteMailRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lock() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))")
            .param("name", INVITE_REQUESTS_LOCK)
            .query(rs -> {
            });
    }

    public Instant now() {
        return jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
    }

    public DailyMails dailyMails(Instant since) {
        return jdbc.sql("SELECT count(*) AS count, min(requested_at) AS oldest FROM invite_mails WHERE requested_at > :since")
            .param("since", Timestamp.from(since))
            .query((rs, rowNum) -> new DailyMails(rs.getInt("count"), instantOrNull(rs, "oldest")))
            .single();
    }

    public AddressMails addressMails(String email, Instant since) {
        return jdbc.sql(
            """
            SELECT count(*) FILTER (WHERE kind = 'LINK') AS links,
                   count(*) FILTER (WHERE kind = 'LIMIT') AS limits,
                   min(requested_at) FILTER (WHERE kind = 'LINK') AS oldest_link
            FROM invite_mails WHERE email = :email AND requested_at > :since
            """)
            .param("email", email)
            .param("since", Timestamp.from(since))
            .query((rs, rowNum) -> new AddressMails(rs.getInt("links"), rs.getInt("limits"), instantOrNull(rs, "oldest_link")))
            .single();
    }

    public @Nullable Invite inviteOf(String email) {
        return jdbc.sql(
            """
            SELECT t.token, t.quota, t.active AND t.expires_at > clock_timestamp() AS valid
            FROM invite_emails e JOIN invite_tokens t ON t.token = e.token WHERE e.email = :email
            """)
            .param("email", email)
            .query((rs, rowNum) -> new Invite(rs.getString("token"), rs.getInt("quota"), rs.getBoolean("valid")))
            .optional()
            .orElse(null);
    }

    public void createInvite(String token, Instant at, Duration lifetime, int quota) {
        jdbc.sql("INSERT INTO invite_tokens (token, created_at, expires_at, quota) VALUES (:token, :at, :expiresAt, :quota)")
            .param("token", token)
            .param("at", Timestamp.from(at))
            .param("expiresAt", Timestamp.from(at.plus(lifetime)))
            .param("quota", quota)
            .update();
    }

    public void extendInvite(String token, Instant at, Duration lifetime) {
        jdbc.sql("UPDATE invite_tokens SET expires_at = :expiresAt WHERE token = :token")
            .param("token", token)
            .param("expiresAt", Timestamp.from(at.plus(lifetime)))
            .update();
    }

    public void setAddressInvite(String email, String token, Instant at) {
        jdbc.sql(
            """
            INSERT INTO invite_emails (email, token, created_at) VALUES (:email, :token, :at)
            ON CONFLICT (email) DO UPDATE SET token = EXCLUDED.token
            """)
            .param("email", email)
            .param("token", token)
            .param("at", Timestamp.from(at))
            .update();
    }

    public void recordRequest(String email, Instant at) {
        jdbc.sql("INSERT INTO invite_requests (email, requested_at) VALUES (:email, :at)")
            .param("email", email)
            .param("at", Timestamp.from(at))
            .update();
    }

    public void insertMail(String email, String token, MailKind kind, Instant at, @Nullable Instant windowFreesAt) {
        jdbc.sql(
            """
            INSERT INTO invite_mails (email, token, kind, requested_at, window_frees_at, next_attempt_at)
            VALUES (:email, :token, :kind, :at, :windowFreesAt, :at)
            """)
            .param("email", email)
            .param("token", token)
            .param("kind", kind.name())
            .param("at", Timestamp.from(at))
            .param("windowFreesAt", windowFreesAt == null ? null : Timestamp.from(windowFreesAt))
            .update();
    }

    public List<Mail> claimDue(int limit) {
        return jdbc.sql(
            """
            SELECT mail_id, email, token, kind, window_frees_at, attempts FROM invite_mails
            WHERE sent_at IS NULL AND failed_at IS NULL AND next_attempt_at <= clock_timestamp()
            ORDER BY next_attempt_at, mail_id LIMIT :limit FOR UPDATE SKIP LOCKED
            """)
            .param("limit", limit)
            .query((rs, rowNum) -> new Mail(
                rs.getLong("mail_id"),
                rs.getString("email"),
                rs.getString("token"),
                MailKind.valueOf(rs.getString("kind")),
                instantOrNull(rs, "window_frees_at"),
                rs.getInt("attempts")))
            .list();
    }

    public void markSent(long mailId) {
        jdbc.sql("UPDATE invite_mails SET sent_at = clock_timestamp(), attempts = attempts + 1 WHERE mail_id = :mailId")
            .param("mailId", mailId)
            .update();
    }

    public void markFailed(long mailId) {
        jdbc.sql("UPDATE invite_mails SET failed_at = clock_timestamp(), attempts = attempts + 1 WHERE mail_id = :mailId")
            .param("mailId", mailId)
            .update();
    }

    public void retryLater(long mailId, Duration pause) {
        jdbc.sql(
            """
            UPDATE invite_mails SET attempts = attempts + 1,
                next_attempt_at = clock_timestamp() + CAST(:millis AS bigint) * interval '1 millisecond'
            WHERE mail_id = :mailId
            """)
            .param("millis", pause.toMillis())
            .param("mailId", mailId)
            .update();
    }

    /** Use is the latest request from the form or the latest visit by the address's link. */
    public int deleteUnusedSince(Instant before) {
        return jdbc.sql(
            """
            DELETE FROM invite_emails e
            WHERE greatest(
                e.created_at,
                (SELECT max(r.requested_at) FROM invite_requests r WHERE r.email = e.email),
                (SELECT v.last_seen FROM invite_visits v WHERE v.token = e.token)
            ) < :before
            """)
            .param("before", Timestamp.from(before))
            .update();
    }

    public @Nullable Deletion requestDeletion(String token, Instant at) {
        return jdbc.sql(
            """
            WITH asked AS (
                SELECT email, delete_at IS NOT NULL AS already_asked FROM invite_emails
                WHERE email IN (SELECT email FROM invite_emails WHERE token = :token UNION SELECT email FROM invite_mails WHERE token = :token)
            )
            UPDATE invite_emails e SET delete_at = coalesce(e.delete_at, :at)
            FROM asked a WHERE e.email = a.email
            RETURNING e.delete_at, a.already_asked
            """)
            .param("token", token)
            .param("at", Timestamp.from(at))
            .query((rs, rowNum) -> new Deletion(rs.getTimestamp("delete_at").toInstant(), rs.getBoolean("already_asked")))
            .optional()
            .orElse(null);
    }

    public int deleteDue() {
        return jdbc.sql("DELETE FROM invite_emails WHERE delete_at <= clock_timestamp()").update();
    }

    private static @Nullable Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
