package com.ledgerlab.gateway.persistence;

import static com.ledgerlab.gateway.domain.RejectReasons.rejectReasonOf;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.gateway.domain.Period;
import com.ledgerlab.gateway.domain.PeriodSummary;
import com.ledgerlab.gateway.domain.StatementCursor;
import com.ledgerlab.gateway.domain.StatementLine;
import com.ledgerlab.gateway.domain.Transfer;
import com.ledgerlab.gateway.domain.TransferStatus;
import com.ledgerlab.gateway.service.LedgerResult;
import com.ledgerlab.gateway.service.TransferRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * A transfer's row is found by (accepted_at, transfer_id). Statements that know the times carry their bounds, so the
 * database looks only into the partitions the rows can be in; those that know the id alone go through transfer_keys.
 */
@Repository
public class TransferRepository {

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One statement for many requests. The key is taken first and the row is written only for a key this statement
     * took, so a repeated key never gets a second row; returns the rows it wrote, a missing id is a key already taken.
     * A key from before {@code keysSince} is past the idempotency window: it is taken again, as a new transfer.
     * Keys go in one order in every batch: two batches holding the same repeated keys in opposite orders each wait
     * for the other's uncommitted insert, and Postgres breaks the deadlock by failing one whole batch.
     */
    public List<Transfer> insertAllIfAbsent(List<TransferRequest> requests, Instant keysSince) {
        if (requests.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
            """
            WITH requested AS (
                SELECT *
                FROM unnest(CAST(:ids AS uuid[]), CAST(:froms AS int[]), CAST(:tos AS int[]), CAST(:amounts AS bigint[]))
                    AS v(transfer_id, from_account, to_account, amount_cents)
            ), taken AS (
                INSERT INTO transfer_keys AS k (transfer_id, accepted_at)
                SELECT transfer_id, clock_timestamp() FROM requested ORDER BY transfer_id
                ON CONFLICT (transfer_id) DO UPDATE SET accepted_at = EXCLUDED.accepted_at WHERE k.accepted_at < :keysSince
                RETURNING k.transfer_id, k.accepted_at
            )
            INSERT INTO transfers (accepted_at, transfer_id, from_account, to_account, amount_cents, status)
            SELECT taken.accepted_at, taken.transfer_id, requested.from_account, requested.to_account, requested.amount_cents, :status
            FROM taken JOIN requested USING (transfer_id)
            RETURNING *
            """)
            .param("ids", requests.stream().map(it -> it.transferId().toString()).toArray(String[]::new))
            .param("froms", requests.stream().map(TransferRequest::fromAccount).toArray(Integer[]::new))
            .param("tos", requests.stream().map(TransferRequest::toAccount).toArray(Integer[]::new))
            .param("amounts", requests.stream().map(TransferRequest::amountCents).toArray(Long[]::new))
            .param("keysSince", Sql.timestamptz(keysSince))
            .param("status", TransferStatus.ACCEPTED.name())
            .query((rs, rowNum) -> toTransfer(rs))
            .list();
    }

    /** The transfers these keys stand for; a key from before {@code keysSince} stands for none. */
    public List<Transfer> findAll(Set<UUID> transferIds, Instant keysSince) {
        if (transferIds.isEmpty()) {
            return List.of();
        }
        var keys = jdbc.sql("SELECT transfer_id, accepted_at FROM transfer_keys WHERE transfer_id = ANY(CAST(:ids AS uuid[])) AND accepted_at >= :keysSince")
            .param("ids", transferIds.stream().map(UUID::toString).toArray(String[]::new))
            .param("keysSince", Sql.timestamptz(keysSince))
            .query((rs, rowNum) -> new TransferKey(Sql.instant(rs, "accepted_at"), rs.getObject("transfer_id", UUID.class)))
            .list();
        if (keys.isEmpty()) {
            return List.of();
        }
        return keyParams(
            jdbc.sql(
                """
                SELECT * FROM transfers
                WHERE accepted_at BETWEEN :earliest AND :latest
                  AND (accepted_at, transfer_id) IN (SELECT unnest(CAST(:acceptedAts AS timestamptz[])), unnest(CAST(:transferIds AS uuid[])))
                """),
            keys)
            .query((rs, rowNum) -> toTransfer(rs))
            .list();
    }

    public @Nullable Transfer find(UUID transferId, Instant keysSince) {
        return findAll(Set.of(transferId), keysSince).stream().findFirst().orElse(null);
    }

    public @Nullable Transfer find(TransferKey key) {
        return jdbc.sql("SELECT * FROM transfers WHERE accepted_at = :acceptedAt AND transfer_id = :id")
            .param("acceptedAt", Sql.timestamptz(key.acceptedAt()))
            .param("id", key.transferId())
            .query((rs, rowNum) -> toTransfer(rs))
            .optional()
            .orElse(null);
    }

    /** Not past this transaction's start: the partitions of the hours ahead hold nothing yet and are left out. */
    public List<TransferCommand> claimUnpublished(int limit) {
        return jdbc.sql(
            """
            SELECT transfer_id, from_account, to_account, amount_cents, accepted_at
            FROM transfers
            WHERE published_at IS NULL AND accepted_at <= now()
            ORDER BY accepted_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
            .param("limit", limit)
            .query((rs, rowNum) -> new TransferCommand(
                rs.getObject("transfer_id", UUID.class),
                rs.getInt("from_account"),
                rs.getInt("to_account"),
                rs.getLong("amount_cents"),
                Sql.instant(rs, "accepted_at")))
            .list();
    }

    /** The result may already have arrived; never move a final status back to PUBLISHED. */
    public void markPublished(List<TransferCommand> commands) {
        if (commands.isEmpty()) {
            return;
        }
        keyParams(
            jdbc.sql(
                """
                UPDATE transfers
                SET published_at = now(),
                    status = CASE WHEN status = :accepted THEN :published ELSE status END
                WHERE accepted_at BETWEEN :earliest AND :latest
                  AND (accepted_at, transfer_id) IN (SELECT unnest(CAST(:acceptedAts AS timestamptz[])), unnest(CAST(:transferIds AS uuid[])))
                """),
            commands.stream().map(it -> new TransferKey(it.acceptedAt(), it.transferId())).toList())
            .param("accepted", TransferStatus.ACCEPTED.name())
            .param("published", TransferStatus.PUBLISHED.name())
            .update();
    }

    /** One statement for a poll of result events; a repeated event finds its row already final. Returns the rows it moved. */
    public List<Transfer> applyResults(List<LedgerResult> results) {
        if (results.isEmpty()) {
            return List.of();
        }
        return keyParams(
            jdbc.sql(
                """
                UPDATE transfers t
                SET status = v.status, reject_reason = v.reason, resulted_at = now()
                FROM unnest(CAST(:acceptedAts AS timestamptz[]), CAST(:transferIds AS uuid[]), CAST(:statuses AS text[]), CAST(:reasons AS text[]))
                    AS v(accepted_at, transfer_id, status, reason)
                WHERE t.accepted_at BETWEEN :earliest AND :latest
                  AND t.accepted_at = v.accepted_at AND t.transfer_id = v.transfer_id AND t.status IN (:accepted, :published)
                RETURNING t.*
                """),
            results.stream().map(it -> new TransferKey(it.acceptedAt(), it.transferId())).toList())
            .param("statuses", results.stream().map(it -> it.outcome().name()).toArray(String[]::new))
            .param("reasons", results.stream().map(LedgerResult::rejectReason).toArray(String[]::new))
            .param("accepted", TransferStatus.ACCEPTED.name())
            .param("published", TransferStatus.PUBLISHED.name())
            .query((rs, rowNum) -> toTransfer(rs))
            .list();
    }

    public PeriodSummary summarize(Period period) {
        var totals = jdbc.sql(
            """
            SELECT count(*) AS accepted,
                   count(published_at) AS published,
                   count(*) FILTER (WHERE status = :posted) AS posted,
                   count(*) FILTER (WHERE status = :rejected) AS rejected
            FROM transfers WHERE accepted_at >= :start AND accepted_at < :end
            """)
            .param("start", Sql.timestamptz(period.start()))
            .param("end", Sql.timestamptz(period.end()))
            .param("posted", TransferStatus.POSTED.name())
            .param("rejected", TransferStatus.REJECTED.name())
            .query().singleRow();
        var reasons = new LinkedHashMap<String, Long>();
        jdbc.sql(
            """
            SELECT reject_reason, count(*) AS n FROM transfers
            WHERE accepted_at >= :start AND accepted_at < :end AND status = :rejected
            GROUP BY reject_reason ORDER BY reject_reason
            """)
            .param("start", Sql.timestamptz(period.start()))
            .param("end", Sql.timestamptz(period.end()))
            .param("rejected", TransferStatus.REJECTED.name())
            .query().listOfRows()
            .forEach(row -> reasons.put((String) Objects.requireNonNull(row.get("reject_reason"), "reject_reason"), toLong(row, "n")));
        return new PeriodSummary(
            period.start(),
            toLong(totals, "accepted"),
            toLong(totals, "published"),
            toLong(totals, "posted"),
            toLong(totals, "rejected"),
            reasons);
    }

    public List<StatementLine> statementPage(Period period, @Nullable StatementCursor after, int limit) {
        var query = jdbc.sql(
            """
            SELECT transfer_id, from_account, to_account, amount_cents, status, reject_reason, accepted_at, resulted_at
            FROM transfers
            WHERE accepted_at >= :start AND accepted_at < :end %s
            ORDER BY accepted_at, transfer_id
            LIMIT :limit
            """.formatted(after == null ? "" : "AND (accepted_at, transfer_id) > (:afterAcceptedAt, :afterId)"))
            .param("start", Sql.timestamptz(period.start()))
            .param("end", Sql.timestamptz(period.end()))
            .param("limit", limit);
        if (after != null) {
            query = query.param("afterAcceptedAt", Sql.timestamptz(after.acceptedAt())).param("afterId", after.transferId());
        }
        return query.query((rs, rowNum) -> new StatementLine(
            rs.getObject("transfer_id", UUID.class),
            rs.getInt("from_account"),
            rs.getInt("to_account"),
            rs.getLong("amount_cents"),
            TransferStatus.valueOf(rs.getString("status")),
            storedRejectReason(rs),
            Sql.instant(rs, "accepted_at"),
            Sql.nullableInstant(rs, "resulted_at"))).list();
    }

    /**
     * At most {@code limit} keys per call, so no single delete holds its locks for long. The time is checked again on
     * the row itself: a key a submission has just taken anew is no longer the one that was selected.
     */
    public int deleteKeysBefore(Instant keysSince, int limit) {
        return jdbc.sql(
            """
            DELETE FROM transfer_keys
            WHERE transfer_id IN (SELECT transfer_id FROM transfer_keys WHERE accepted_at < :keysSince LIMIT :limit)
              AND accepted_at < :keysSince
            """)
            .param("keysSince", Sql.timestamptz(keysSince))
            .param("limit", limit)
            .update();
    }

    private static JdbcClient.StatementSpec keyParams(JdbcClient.StatementSpec statement, Collection<TransferKey> keys) {
        var acceptedAts = keys.stream().map(TransferKey::acceptedAt).toList();
        return statement
            .param("earliest", Sql.timestamptz(acceptedAts.stream().min(Instant::compareTo).orElseThrow()))
            .param("latest", Sql.timestamptz(acceptedAts.stream().max(Instant::compareTo).orElseThrow()))
            .param("acceptedAts", Sql.timestamptzArray(acceptedAts))
            .param("transferIds", keys.stream().map(it -> it.transferId().toString()).toArray(String[]::new));
    }

    private static Transfer toTransfer(ResultSet rs) throws SQLException {
        return new Transfer(
            rs.getObject("transfer_id", UUID.class),
            rs.getInt("from_account"),
            rs.getInt("to_account"),
            rs.getLong("amount_cents"),
            TransferStatus.valueOf(rs.getString("status")),
            storedRejectReason(rs),
            Sql.instant(rs, "accepted_at"),
            Sql.nullableInstant(rs, "resulted_at"));
    }

    private static @Nullable RejectReason storedRejectReason(ResultSet rs) throws SQLException {
        var stored = rs.getString("reject_reason");
        return stored == null ? null : rejectReasonOf(stored);
    }

    private static long toLong(Map<String, @Nullable Object> row, String column) {
        return ((Number) Objects.requireNonNull(row.get(column), column)).longValue();
    }
}
