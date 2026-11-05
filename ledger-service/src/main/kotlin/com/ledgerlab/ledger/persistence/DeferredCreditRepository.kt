package com.ledgerlab.ledger.persistence

import com.ledgerlab.ledger.domain.AccountKey
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.UUID

data class AppliedCredit(val account: AccountKey, val amountCents: Long, val waited: Duration)

@Repository
class DeferredCreditRepository(private val jdbc: JdbcClient) {

    fun insertAll(credits: Map<AccountKey, Long>) {
        if (credits.isEmpty()) return
        val entries = credits.entries.toList()
        jdbc.sql(
            """
            INSERT INTO deferred_credits (run_id, account_no, amount_cents)
            SELECT * FROM unnest(CAST(:runIds AS uuid[]), CAST(:accountNos AS integer[]), CAST(:amounts AS bigint[]))
            """,
        )
            .param("runIds", entries.map { it.key.runId.toString() }.toTypedArray())
            .param("accountNos", entries.map { it.key.accountNo }.toTypedArray())
            .param("amounts", entries.map { it.value }.toTypedArray())
            .update()
    }

    /** Held until the transaction ends; two appliers would lock the same receivers and wait for each other. */
    fun tryLockApplier(): Boolean =
        jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:name, 0))")
            .param("name", APPLIER_LOCK)
            .query(Boolean::class.java)
            .single()

    /**
     * Not "rows after the last applied id": batches commit out of id order, so a row with a lower id can appear
     * after a higher one was applied.
     */
    fun takePending(limit: Int): List<AppliedCredit> =
        jdbc.sql(
            """
            DELETE FROM deferred_credits
            WHERE id IN (SELECT id FROM deferred_credits ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED)
            RETURNING run_id, account_no, amount_cents,
                      (extract(epoch FROM clock_timestamp() - created_at) * 1000)::bigint AS waited_ms
            """,
        )
            .param("limit", limit)
            .query { rs, _ ->
                AppliedCredit(
                    AccountKey(rs.getObject("run_id", UUID::class.java), rs.getInt("account_no")),
                    rs.getLong("amount_cents"),
                    Duration.ofMillis(rs.getLong("waited_ms")),
                )
            }
            .list()

    fun deleteRun(runId: UUID): Int =
        jdbc.sql("DELETE FROM deferred_credits WHERE run_id = :runId").param("runId", runId).update()

    private companion object {
        const val APPLIER_LOCK = "ledger.deferred-credit-applier"
    }
}
