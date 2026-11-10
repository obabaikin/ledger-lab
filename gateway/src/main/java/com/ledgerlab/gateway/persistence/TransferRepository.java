package com.ledgerlab.gateway.persistence;

import static com.ledgerlab.gateway.domain.RejectReasons.rejectReasonOf;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.gateway.domain.RunSummary;
import com.ledgerlab.gateway.domain.StatementLine;
import com.ledgerlab.gateway.domain.Transfer;
import com.ledgerlab.gateway.domain.TransferStatus;
import com.ledgerlab.gateway.service.LedgerResult;
import com.ledgerlab.gateway.service.TransferRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepository {

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One statement for many requests; returns only the rows it inserted, so a missing id is a key already taken.
     * Keys go in one order in every batch: two batches holding the same repeated keys in opposite orders each wait
     * for the other's uncommitted insert, and Postgres breaks the deadlock by failing one whole batch.
     */
    public List<Transfer> insertAllIfAbsent(List<TransferRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
            """
            INSERT INTO transfers (transfer_id, run_id, from_account, to_account, amount_cents, status)
            SELECT v.transfer_id, v.run_id, v.from_account, v.to_account, v.amount_cents, :status
            FROM unnest(CAST(:ids AS uuid[]), CAST(:runIds AS uuid[]), CAST(:froms AS int[]), CAST(:tos AS int[]), CAST(:amounts AS bigint[]))
                AS v(transfer_id, run_id, from_account, to_account, amount_cents)
            ORDER BY v.transfer_id
            ON CONFLICT (transfer_id) DO NOTHING
            RETURNING *
            """)
            .param("ids", requests.stream().map(it -> it.transferId().toString()).toArray(String[]::new))
            .param("runIds", requests.stream().map(it -> it.runId().toString()).toArray(String[]::new))
            .param("froms", requests.stream().map(TransferRequest::fromAccount).toArray(Integer[]::new))
            .param("tos", requests.stream().map(TransferRequest::toAccount).toArray(Integer[]::new))
            .param("amounts", requests.stream().map(TransferRequest::amountCents).toArray(Long[]::new))
            .param("status", TransferStatus.ACCEPTED.name())
            .query((rs, rowNum) -> toTransfer(rs))
            .list();
    }

    public List<Transfer> findAll(Set<UUID> transferIds) {
        if (transferIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM transfers WHERE transfer_id = ANY(CAST(:ids AS uuid[]))")
            .param("ids", transferIds.stream().map(UUID::toString).toArray(String[]::new))
            .query((rs, rowNum) -> toTransfer(rs))
            .list();
    }

    public @Nullable Transfer find(UUID transferId) {
        return jdbc.sql("SELECT * FROM transfers WHERE transfer_id = :id")
            .param("id", transferId)
            .query((rs, rowNum) -> toTransfer(rs))
            .optional()
            .orElse(null);
    }

    public List<TransferCommand> claimUnpublished(int limit) {
        return jdbc.sql(
            """
            SELECT transfer_id, run_id, from_account, to_account, amount_cents, accepted_at
            FROM transfers
            WHERE published_at IS NULL
            ORDER BY accepted_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
            .param("limit", limit)
            .query((rs, rowNum) -> new TransferCommand(
                rs.getObject("run_id", UUID.class),
                rs.getObject("transfer_id", UUID.class),
                rs.getInt("from_account"),
                rs.getInt("to_account"),
                rs.getLong("amount_cents"),
                rs.getObject("accepted_at", Timestamp.class).toInstant()))
            .list();
    }

    /** The result may already have arrived; never move a final status back to PUBLISHED. */
    public void markPublished(List<UUID> transferIds) {
        if (transferIds.isEmpty()) {
            return;
        }
        jdbc.sql(
            """
            UPDATE transfers
            SET published_at = now(),
                status = CASE WHEN status = :accepted THEN :published ELSE status END
            WHERE transfer_id = ANY(CAST(:ids AS uuid[]))
            """)
            .param("accepted", TransferStatus.ACCEPTED.name())
            .param("published", TransferStatus.PUBLISHED.name())
            .param("ids", transferIds.stream().map(UUID::toString).toArray(String[]::new))
            .update();
    }

    /** One statement for a poll of result events; a repeated event finds its row already final. Returns the rows it moved. */
    public List<Transfer> applyResults(List<LedgerResult> results) {
        if (results.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
            """
            UPDATE transfers t
            SET status = v.status, reject_reason = v.reason, resulted_at = now()
            FROM unnest(CAST(:ids AS uuid[]), CAST(:statuses AS text[]), CAST(:reasons AS text[])) AS v(transfer_id, status, reason)
            WHERE t.transfer_id = v.transfer_id AND t.status IN (:accepted, :published)
            RETURNING t.*
            """)
            .param("ids", results.stream().map(it -> it.transferId().toString()).toArray(String[]::new))
            .param("statuses", results.stream().map(it -> it.outcome().name()).toArray(String[]::new))
            .param("reasons", results.stream().map(LedgerResult::rejectReason).toArray(String[]::new))
            .param("accepted", TransferStatus.ACCEPTED.name())
            .param("published", TransferStatus.PUBLISHED.name())
            .query((rs, rowNum) -> toTransfer(rs))
            .list();
    }

    public RunSummary summarize(UUID runId) {
        var totals = jdbc.sql(
            """
            SELECT count(*) AS accepted,
                   count(published_at) AS published,
                   count(*) FILTER (WHERE status = :posted) AS posted,
                   count(*) FILTER (WHERE status = :rejected) AS rejected,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM (resulted_at - accepted_at)) * 1000) AS p50,
                   percentile_cont(0.99) WITHIN GROUP (ORDER BY extract(epoch FROM (resulted_at - accepted_at)) * 1000) AS p99
            FROM transfers WHERE run_id = :runId
            """)
            .param("runId", runId)
            .param("posted", TransferStatus.POSTED.name())
            .param("rejected", TransferStatus.REJECTED.name())
            .query().singleRow();
        var reasons = new LinkedHashMap<String, Long>();
        jdbc.sql("SELECT reject_reason, count(*) AS n FROM transfers WHERE run_id = :runId AND status = :rejected GROUP BY reject_reason")
            .param("runId", runId)
            .param("rejected", TransferStatus.REJECTED.name())
            .query().listOfRows()
            .forEach(row -> reasons.put((String) Objects.requireNonNull(row.get("reject_reason"), "reject_reason"), toLong(row, "n")));
        return new RunSummary(
            runId,
            toLong(totals, "accepted"),
            toLong(totals, "published"),
            toLong(totals, "posted"),
            toLong(totals, "rejected"),
            reasons,
            toNullableDouble(totals, "p50"),
            toNullableDouble(totals, "p99"));
    }

    public boolean belongsTo(UUID transferId, UUID runId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM transfers WHERE transfer_id = :id AND run_id = :runId)")
            .param("id", transferId)
            .param("runId", runId)
            .query(Boolean.class)
            .single();
    }

    public List<StatementLine> statementPage(UUID runId, @Nullable UUID after, int limit) {
        var afterClause = after == null
            ? ""
            : "AND (accepted_at, transfer_id) > (SELECT accepted_at, transfer_id FROM transfers WHERE transfer_id = :after)";
        var query = jdbc.sql(
            """
            SELECT transfer_id, from_account, to_account, amount_cents, status, reject_reason, accepted_at, resulted_at
            FROM transfers
            WHERE run_id = :runId %s
            ORDER BY accepted_at, transfer_id
            LIMIT :limit
            """.formatted(afterClause))
            .param("runId", runId)
            .param("limit", limit);
        if (after != null) {
            query = query.param("after", after);
        }
        return query.query((rs, rowNum) -> new StatementLine(
            rs.getObject("transfer_id", UUID.class),
            rs.getInt("from_account"),
            rs.getInt("to_account"),
            rs.getLong("amount_cents"),
            TransferStatus.valueOf(rs.getString("status")),
            storedRejectReason(rs),
            rs.getObject("accepted_at", Timestamp.class).toInstant(),
            nullableInstant(rs, "resulted_at"))).list();
    }

    public int deleteRun(UUID runId) {
        return jdbc.sql("DELETE FROM transfers WHERE run_id = :runId").param("runId", runId).update();
    }

    private static Transfer toTransfer(ResultSet rs) throws SQLException {
        return new Transfer(
            rs.getObject("transfer_id", UUID.class),
            rs.getObject("run_id", UUID.class),
            rs.getInt("from_account"),
            rs.getInt("to_account"),
            rs.getLong("amount_cents"),
            TransferStatus.valueOf(rs.getString("status")),
            storedRejectReason(rs),
            rs.getObject("accepted_at", Timestamp.class).toInstant(),
            nullableInstant(rs, "resulted_at"));
    }

    private static @Nullable RejectReason storedRejectReason(ResultSet rs) throws SQLException {
        var stored = rs.getString("reject_reason");
        return stored == null ? null : rejectReasonOf(stored);
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getObject(column, Timestamp.class);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static long toLong(Map<String, @Nullable Object> row, String column) {
        return ((Number) Objects.requireNonNull(row.get(column), column)).longValue();
    }

    private static @Nullable Double toNullableDouble(Map<String, @Nullable Object> row, String column) {
        var value = (Number) row.get(column);
        return value == null ? null : value.doubleValue();
    }
}
