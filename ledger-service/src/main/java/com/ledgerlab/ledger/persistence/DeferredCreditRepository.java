package com.ledgerlab.ledger.persistence;

import com.ledgerlab.ledger.domain.AccountKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DeferredCreditRepository {

    private static final String APPLIER_LOCK = "ledger.deferred-credit-applier";

    private final JdbcClient jdbc;

    public DeferredCreditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(Map<AccountKey, Long> credits) {
        if (credits.isEmpty()) {
            return;
        }
        var entries = List.copyOf(credits.entrySet());
        jdbc.sql(
            """
            INSERT INTO deferred_credits (run_id, account_no, amount_cents)
            SELECT * FROM unnest(CAST(:runIds AS uuid[]), CAST(:accountNos AS integer[]), CAST(:amounts AS bigint[]))
            """)
            .param("runIds", entries.stream().map(it -> it.getKey().runId().toString()).toArray(String[]::new))
            .param("accountNos", entries.stream().map(it -> it.getKey().accountNo()).toArray(Integer[]::new))
            .param("amounts", entries.stream().map(Map.Entry::getValue).toArray(Long[]::new))
            .update();
    }

    /** Held until the transaction ends; two appliers would lock the same receivers and wait for each other. */
    public boolean tryLockApplier() {
        return jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:name, 0))")
            .param("name", APPLIER_LOCK)
            .query(Boolean.class)
            .single();
    }

    /**
     * Not "rows after the last applied id": batches commit out of id order, so a row with a lower id can appear
     * after a higher one was applied.
     */
    public List<AppliedCredit> takePending(int limit) {
        return jdbc.sql(
            """
            DELETE FROM deferred_credits
            WHERE id IN (SELECT id FROM deferred_credits ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED)
            RETURNING run_id, account_no, amount_cents,
                      (extract(epoch FROM clock_timestamp() - created_at) * 1000)::bigint AS waited_ms
            """)
            .param("limit", limit)
            .query((rs, rowNum) -> new AppliedCredit(
                new AccountKey(rs.getObject("run_id", UUID.class), rs.getInt("account_no")),
                rs.getLong("amount_cents"),
                Duration.ofMillis(rs.getLong("waited_ms"))))
            .list();
    }

    public int deleteRun(UUID runId) {
        return jdbc.sql("DELETE FROM deferred_credits WHERE run_id = :runId").param("runId", runId).update();
    }
}
