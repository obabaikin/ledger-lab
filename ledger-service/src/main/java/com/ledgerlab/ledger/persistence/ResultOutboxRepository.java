package com.ledgerlab.ledger.persistence;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.common.TransferResultEvent;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ResultOutboxRepository {

    private final JdbcClient jdbc;

    public ResultOutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Claims a batch for this transaction; other publishers skip the claimed rows. */
    public List<TransferResultEvent> claimUnpublished(int limit) {
        return jdbc.sql(
            """
            SELECT run_id, transfer_id, outcome, reject_reason, processed_at
            FROM transfers
            WHERE result_published_at IS NULL
            ORDER BY processed_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
            .param("limit", limit)
            .query((rs, rowNum) -> {
                var reason = rs.getString("reject_reason");
                return new TransferResultEvent(
                    rs.getObject("run_id", UUID.class),
                    rs.getObject("transfer_id", UUID.class),
                    TransferOutcome.valueOf(rs.getString("outcome")),
                    reason == null ? null : RejectReason.valueOf(reason),
                    rs.getObject("processed_at", Timestamp.class).toInstant());
            })
            .list();
    }

    public void markPublished(List<TransferResultEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        jdbc.sql(
            """
            UPDATE transfers SET result_published_at = now()
            WHERE (run_id, transfer_id) IN (
                SELECT unnest(CAST(:runIds AS uuid[])), unnest(CAST(:transferIds AS uuid[]))
            )
            """)
            .param("runIds", events.stream().map(it -> it.runId().toString()).toArray(String[]::new))
            .param("transferIds", events.stream().map(it -> it.transferId().toString()).toArray(String[]::new))
            .update();
    }
}
