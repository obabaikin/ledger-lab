package com.ledgerlab.reporting.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransferStatsRepository {

    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;

    public TransferStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertAccepted(List<AcceptedTransfer> rows) {
        jdbc.batchUpdate(
            """
            INSERT INTO transfer_stats (transfer_id, run_id, amount_cents, accepted_at) VALUES (?, ?, ?, ?)
            ON CONFLICT (transfer_id) DO UPDATE SET amount_cents = EXCLUDED.amount_cents, accepted_at = EXCLUDED.accepted_at
            """,
            lockOrdered(rows, AcceptedTransfer::transferId),
            BATCH_SIZE,
            (ps, row) -> {
                ps.setObject(1, row.transferId());
                ps.setObject(2, row.runId());
                ps.setLong(3, row.amountCents());
                ps.setTimestamp(4, Timestamp.from(row.acceptedAt()));
            });
    }

    public void upsertOutcomes(List<TransferOutcomeRow> rows) {
        jdbc.batchUpdate(
            """
            INSERT INTO transfer_stats (transfer_id, run_id, outcome, reject_reason, processed_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (transfer_id) DO UPDATE
                SET outcome = EXCLUDED.outcome, reject_reason = EXCLUDED.reject_reason, processed_at = EXCLUDED.processed_at
            """,
            lockOrdered(rows, TransferOutcomeRow::transferId),
            BATCH_SIZE,
            (ps, row) -> {
                ps.setObject(1, row.transferId());
                ps.setObject(2, row.runId());
                ps.setString(3, row.outcome());
                ps.setString(4, row.rejectReason());
                ps.setTimestamp(5, Timestamp.from(row.processedAt()));
            });
    }

    /**
     * Commands and results of the same transfers can be written by different instances at once; taking row locks
     * in one order keeps them from deadlocking. The rewritten multi-row insert also rejects a key twice in one
     * statement, and a redelivered message can bring the same transfer twice in one batch.
     */
    private static <T> List<T> lockOrdered(List<T> rows, Function<T, UUID> key) {
        var ordered = new TreeMap<UUID, T>();
        rows.forEach(row -> ordered.put(key.apply(row), row));
        return List.copyOf(ordered.values());
    }

    public int deleteRun(UUID runId) {
        return jdbc.update("DELETE FROM transfer_stats WHERE run_id = ?", runId);
    }
}
