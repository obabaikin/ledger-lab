package com.ledgerlab.ledger.persistence;

import com.ledgerlab.common.TransferOutcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepository {

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Set<TransferKey> findRecorded(Set<TransferKey> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        return jdbc.sql(
            """
            SELECT run_id, transfer_id FROM transfers
            WHERE (run_id, transfer_id) IN (SELECT unnest(CAST(:runIds AS uuid[])), unnest(CAST(:transferIds AS uuid[])))
            """)
            .param("runIds", keys.stream().map(it -> it.runId().toString()).toArray(String[]::new))
            .param("transferIds", keys.stream().map(it -> it.transferId().toString()).toArray(String[]::new))
            .query((rs, rowNum) -> toKey(rs))
            .set();
    }

    /**
     * Primary key (run_id, transfer_id) is the idempotency guard.
     * Returns the keys this call inserted; a transfer already recorded, also by a concurrent transaction, is skipped.
     */
    public Set<TransferKey> insertAllIfAbsent(List<TransferRow> rows) {
        if (rows.isEmpty()) {
            return Set.of();
        }
        return jdbc.sql(
            """
            INSERT INTO transfers (run_id, transfer_id, from_account, to_account, amount_cents, outcome, reject_reason)
            SELECT * FROM unnest(
                CAST(:runIds AS uuid[]), CAST(:transferIds AS uuid[]), CAST(:froms AS integer[]), CAST(:tos AS integer[]),
                CAST(:amounts AS bigint[]), CAST(:outcomes AS text[]), CAST(:reasons AS text[])
            )
            ON CONFLICT (run_id, transfer_id) DO NOTHING
            RETURNING run_id, transfer_id
            """)
            .param("runIds", rows.stream().map(it -> it.command().runId().toString()).toArray(String[]::new))
            .param("transferIds", rows.stream().map(it -> it.command().transferId().toString()).toArray(String[]::new))
            .param("froms", rows.stream().map(it -> it.command().fromAccount()).toArray(Integer[]::new))
            .param("tos", rows.stream().map(it -> it.command().toAccount()).toArray(Integer[]::new))
            .param("amounts", rows.stream().map(it -> it.command().amountCents()).toArray(Long[]::new))
            .param("outcomes", rows.stream()
                .map(it -> (it.rejectReason() == null ? TransferOutcome.POSTED : TransferOutcome.REJECTED).name())
                .toArray(String[]::new))
            .param("reasons", rows.stream()
                .map(it -> it.rejectReason() == null ? null : it.rejectReason().name())
                .toArray(String[]::new))
            .query((rs, rowNum) -> toKey(rs))
            .set();
    }

    public int deleteRun(UUID runId) {
        return jdbc.sql("DELETE FROM transfers WHERE run_id = :runId").param("runId", runId).update();
    }

    private static TransferKey toKey(ResultSet rs) throws SQLException {
        return new TransferKey(rs.getObject("run_id", UUID.class), rs.getObject("transfer_id", UUID.class));
    }
}
