package com.ledgerlab.ledger.persistence;

import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.ledger.domain.TransferKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
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
        return keyParams(
            jdbc.sql(
                """
                SELECT accepted_at, transfer_id FROM transfers
                WHERE accepted_at BETWEEN :earliest AND :latest
                  AND (accepted_at, transfer_id) IN (SELECT unnest(CAST(:acceptedAts AS timestamptz[])), unnest(CAST(:transferIds AS uuid[])))
                """),
            keys)
            .query((rs, rowNum) -> toKey(rs))
            .set();
    }

    /**
     * Primary key (accepted_at, transfer_id) is the idempotency guard.
     * Returns the keys this call inserted; a transfer already recorded, also by a concurrent transaction, is skipped.
     * A pool posting has no result event to wait for and is recorded as published.
     */
    public Set<TransferKey> insertAllIfAbsent(List<TransferRow> rows, TransferKind kind) {
        if (rows.isEmpty()) {
            return Set.of();
        }
        return jdbc.sql(
            """
            INSERT INTO transfers (accepted_at, transfer_id, kind, from_account, to_account, amount_cents, outcome, reject_reason, result_published_at)
            SELECT v.accepted_at, v.transfer_id, :kind, v.from_account, v.to_account, v.amount_cents, v.outcome, v.reject_reason,
                   CASE WHEN :published THEN now() END
            FROM unnest(
                CAST(:acceptedAts AS timestamptz[]), CAST(:transferIds AS uuid[]), CAST(:froms AS integer[]), CAST(:tos AS integer[]),
                CAST(:amounts AS bigint[]), CAST(:outcomes AS text[]), CAST(:reasons AS text[])
            ) AS v(accepted_at, transfer_id, from_account, to_account, amount_cents, outcome, reject_reason)
            ON CONFLICT (accepted_at, transfer_id) DO NOTHING
            RETURNING accepted_at, transfer_id
            """)
            .param("kind", kind.name())
            .param("published", kind == TransferKind.POOL)
            .param("acceptedAts", Sql.timestamptzArray(rows.stream().map(it -> it.command().acceptedAt()).toList()))
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

    /** The bounds let the database look only into the partitions the keys can be in. */
    static JdbcClient.StatementSpec keyParams(JdbcClient.StatementSpec statement, Collection<TransferKey> keys) {
        var acceptedAts = keys.stream().map(TransferKey::acceptedAt).toList();
        return statement
            .param("earliest", Sql.timestamptz(acceptedAts.stream().min(Instant::compareTo).orElseThrow()))
            .param("latest", Sql.timestamptz(acceptedAts.stream().max(Instant::compareTo).orElseThrow()))
            .param("acceptedAts", Sql.timestamptzArray(acceptedAts))
            .param("transferIds", keys.stream().map(it -> it.transferId().toString()).toArray(String[]::new));
    }

    private static TransferKey toKey(ResultSet rs) throws SQLException {
        return new TransferKey(Sql.instant(rs, "accepted_at"), rs.getObject("transfer_id", UUID.class));
    }
}
