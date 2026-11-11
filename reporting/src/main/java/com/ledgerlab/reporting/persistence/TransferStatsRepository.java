package com.ledgerlab.reporting.persistence;

import com.ledgerlab.common.TransferOutcome;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Each write returns only the rows it changed, so the caller adds a transfer to the numbers once however often its
 * events are delivered. Whichever event comes first creates the row; the other completes it. A row made by a command
 * has an amount and no outcome, one made by a result has an outcome and no amount: that tells a created row from a
 * completed one.
 */
@Repository
public class TransferStatsRepository {

    private static final String POSTED = TransferOutcome.POSTED.name();
    private static final String REJECTED = TransferOutcome.REJECTED.name();

    private final JdbcClient jdbc;

    public TransferStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<StatsChange> writeAccepted(List<AcceptedTransfer> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var ordered = lockOrdered(rows, AcceptedTransfer::key);
        return jdbc.sql(
            """
            INSERT INTO transfer_stats AS s (accepted_at, transfer_id, amount_cents)
            SELECT * FROM unnest(CAST(:acceptedAts AS timestamptz[]), CAST(:transferIds AS uuid[]), CAST(:amounts AS bigint[]))
            ON CONFLICT (accepted_at, transfer_id) DO UPDATE SET amount_cents = EXCLUDED.amount_cents WHERE s.amount_cents IS NULL
            RETURNING s.accepted_at, s.amount_cents, s.outcome
            """)
            .param("acceptedAts", Sql.timestamptzArray(ordered.stream().map(it -> it.key().acceptedAt()).toList()))
            .param("transferIds", ordered.stream().map(it -> it.key().transferId().toString()).toArray(String[]::new))
            .param("amounts", ordered.stream().map(AcceptedTransfer::amountCents).toArray(Long[]::new))
            .query((rs, rowNum) -> {
                var outcome = rs.getString("outcome");
                return new StatsChange(
                    Sql.instant(rs, "accepted_at"),
                    outcome == null,
                    true,
                    false,
                    false,
                    POSTED.equals(outcome) ? rs.getLong("amount_cents") : 0,
                    null);
            })
            .list();
    }

    public List<StatsChange> writeOutcomes(List<TransferOutcomeRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var ordered = lockOrdered(rows, TransferOutcomeRow::key);
        return jdbc.sql(
            """
            INSERT INTO transfer_stats AS s (accepted_at, transfer_id, outcome, reject_reason, processed_at)
            SELECT * FROM unnest(
                CAST(:acceptedAts AS timestamptz[]), CAST(:transferIds AS uuid[]), CAST(:outcomes AS text[]), CAST(:reasons AS text[]),
                CAST(:processedAts AS timestamptz[]))
            ON CONFLICT (accepted_at, transfer_id) DO UPDATE
                SET outcome = EXCLUDED.outcome, reject_reason = EXCLUDED.reject_reason, processed_at = EXCLUDED.processed_at
                WHERE s.outcome IS NULL
            RETURNING s.accepted_at, s.amount_cents, s.outcome, s.reject_reason
            """)
            .param("acceptedAts", Sql.timestamptzArray(ordered.stream().map(it -> it.key().acceptedAt()).toList()))
            .param("transferIds", ordered.stream().map(it -> it.key().transferId().toString()).toArray(String[]::new))
            .param("outcomes", ordered.stream().map(TransferOutcomeRow::outcome).toArray(String[]::new))
            .param("reasons", ordered.stream().map(TransferOutcomeRow::rejectReason).toArray(String[]::new))
            .param("processedAts", Sql.timestamptzArray(ordered.stream().map(TransferOutcomeRow::processedAt).toList()))
            .query((rs, rowNum) -> {
                var amount = rs.getLong("amount_cents");
                var createdByThisResult = rs.wasNull();
                var outcome = rs.getString("outcome");
                return new StatsChange(
                    Sql.instant(rs, "accepted_at"),
                    createdByThisResult,
                    false,
                    POSTED.equals(outcome),
                    REJECTED.equals(outcome),
                    POSTED.equals(outcome) ? amount : 0,
                    rs.getString("reject_reason"));
            })
            .list();
    }

    /**
     * Commands and results of the same transfers can be written by different instances at once; taking row locks
     * in one order keeps them from deadlocking. One statement also cannot write a row twice, and a redelivered
     * message can bring the same transfer twice in one batch.
     */
    private static <T> Collection<T> lockOrdered(List<T> rows, Function<T, TransferKey> key) {
        var ordered = new TreeMap<TransferKey, T>();
        rows.forEach(row -> ordered.putIfAbsent(key.apply(row), row));
        return ordered.values();
    }
}
