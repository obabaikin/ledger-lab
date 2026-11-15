package com.ledgerlab.ledger.persistence;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.common.TransferResultEvent;
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
            SELECT accepted_at, transfer_id, outcome, reject_reason, processed_at
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
                    rs.getObject("transfer_id", UUID.class),
                    TransferOutcome.valueOf(rs.getString("outcome")),
                    reason == null ? null : RejectReason.valueOf(reason),
                    Sql.instant(rs, "processed_at"),
                    Sql.instant(rs, "accepted_at"));
            })
            .list();
    }

    public void markPublished(List<TransferResultEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        TransferRepository.keyParams(
            jdbc.sql(
                """
                UPDATE transfers SET result_published_at = now()
                WHERE accepted_at BETWEEN :earliest AND :latest
                  AND (accepted_at, transfer_id) IN (SELECT unnest(CAST(:acceptedAts AS timestamptz[])), unnest(CAST(:transferIds AS uuid[])))
                """),
            events.stream().map(it -> new TransferKey(it.acceptedAt(), it.transferId())).toList())
            .update();
    }
}
