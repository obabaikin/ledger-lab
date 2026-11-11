package com.ledgerlab.generator;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JournalRepository {

    private final JdbcClient jdbc;

    public JournalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record IntentKey(Instant sentAt, UUID transferId) {

        /** Before every intent sent in the period: no transfer has this id. */
        static IntentKey before(Period sentIn) {
            return new IntentKey(sentIn.start(), new UUID(0L, 0L));
        }
    }

    public List<IntentKey> unknown(Period sentIn, IntentKey after, int limit) {
        return jdbc.sql(
            """
            SELECT i.sent_at, i.transfer_id
            FROM intents i
            WHERE i.sent_at >= :start AND i.sent_at < :end
              AND (i.sent_at, i.transfer_id) > (:afterSentAt, :afterId)
              AND NOT EXISTS (
                  SELECT 1 FROM outcomes o
                  WHERE o.sent_at = i.sent_at AND o.transfer_id = i.transfer_id AND o.sent_at >= :start AND o.sent_at < :end)
            ORDER BY i.sent_at, i.transfer_id
            LIMIT :limit
            """)
            .param("start", Sql.timestamptz(sentIn.start()))
            .param("end", Sql.timestamptz(sentIn.end()))
            .param("afterSentAt", Sql.timestamptz(after.sentAt()))
            .param("afterId", after.transferId())
            .param("limit", limit)
            .query((rs, row) -> new IntentKey(rs.getObject("sent_at", OffsetDateTime.class).toInstant(), rs.getObject("transfer_id", UUID.class)))
            .list();
    }

    public UnknownResolved resolved(Period sentIn) {
        return jdbc.sql(
            """
            SELECT count(*) FILTER (WHERE outcome = :accepted) AS accepted,
                   count(*) FILTER (WHERE outcome = :notAccepted) AS not_accepted
            FROM outcomes
            WHERE sent_at >= :start AND sent_at < :end
            """)
            .param("accepted", JournalOutcome.UNKNOWN_ACCEPTED.name())
            .param("notAccepted", JournalOutcome.UNKNOWN_NOT_ACCEPTED.name())
            .param("start", Sql.timestamptz(sentIn.start()))
            .param("end", Sql.timestamptz(sentIn.end()))
            .query((rs, row) -> new UnknownResolved(rs.getLong("accepted"), rs.getLong("not_accepted")))
            .single();
    }
}
