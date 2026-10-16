package com.ledgerlab.ledger

import com.ledgerlab.ledger.domain.ReconciliationCheck
import com.ledgerlab.ledger.service.LedgerService
import com.ledgerlab.ledger.service.RunService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** Reconciliation must notice corruption that keeps the totals intact. */
class ReconciliationTest : IntegrationTest() {

    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var runs: RunService
    @Autowired lateinit var jdbc: JdbcClient

    private fun runWithOnePosting(): Pair<UUID, UUID> {
        val runId = UUID.randomUUID()
        runs.openAccounts(runId, setup(accountCount = 3, openingBalanceCents = 1_000))
        val command = transfer(runId, from = 1, to = 2, amountCents = 100)
        ledger.apply(command)
        assertThat(runs.reconcile(runId).balanced).isTrue()
        return runId to command.transferId
    }

    @Test
    fun `sums debits and credits of the journal apart, each as a positive amount, without a rejected transfer`() {
        val (runId, _) = runWithOnePosting()
        ledger.apply(transfer(runId, from = 2, to = 3, amountCents = 50))
        ledger.apply(transfer(runId, from = 3, to = 1, amountCents = 1_000_000))

        val report = runs.reconcile(runId)

        assertThat(report.debitsCents).isEqualTo(150)
        assertThat(report.creditsCents).isEqualTo(150)
        assertThat(report.openingTotalCents).isEqualTo(report.balanceTotalCents)
        assertThat(report.rejectedTransfers).isEqualTo(1)
    }

    @Test
    fun `detects a credit booked to the wrong account even when every total still agrees`() {
        val (runId, transferId) = runWithOnePosting()
        // Move 100 from account 1 to 3 in the journal without touching balances: totals stay intact.
        jdbc.sql("UPDATE journal_entries SET account_no = 3 WHERE run_id = :runId AND transfer_id = :t AND amount_cents > 0")
            .param("runId", runId).param("t", transferId).update()
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 100 WHERE run_id = :runId AND account_no = 3")
            .param("runId", runId).update()
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents - 100 WHERE run_id = :runId AND account_no = 2")
            .param("runId", runId).update()

        val report = runs.reconcile(runId)

        assertThat(report.failedChecks).containsExactly(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG)
    }

    @Test
    fun `detects a posting with a third journal row balanced by a missing row elsewhere`() {
        val (runId, transferId) = runWithOnePosting()
        val second = transfer(runId, from = 2, to = 3, amountCents = 50)
        ledger.apply(second)
        // Reattach one of the second posting's rows to the first posting: counts and sums per run stay the same.
        jdbc.sql("UPDATE journal_entries SET transfer_id = :first WHERE run_id = :runId AND transfer_id = :second AND amount_cents > 0")
            .param("first", transferId).param("runId", runId).param("second", second.transferId).update()

        val report = runs.reconcile(runId)

        assertThat(report.failedChecks).containsExactly(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG)
    }

    @Test
    fun `detects journal rows attached to a rejected transfer`() {
        val (runId, _) = runWithOnePosting()
        val rejected = transfer(runId, from = 1, to = 2, amountCents = 1_000_000)
        ledger.apply(rejected)
        jdbc.sql("INSERT INTO journal_entries (run_id, transfer_id, account_no, amount_cents) VALUES (:runId, :t, 1, -5), (:runId, :t, 2, 5)")
            .param("runId", runId).param("t", rejected.transferId).update()

        val report = runs.reconcile(runId)

        assertThat(report.failedChecks).contains(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG)
    }

    @Test
    fun `detects a posted transfer with no journal rows`() {
        val (runId, transferId) = runWithOnePosting()
        // Drop both rows and undo their balance effect: only the transfer log still shows the posting.
        jdbc.sql("DELETE FROM journal_entries WHERE run_id = :runId AND transfer_id = :t")
            .param("runId", runId).param("t", transferId).update()
        jdbc.sql("UPDATE accounts SET balance_cents = opening_balance_cents WHERE run_id = :runId")
            .param("runId", runId).update()

        val report = runs.reconcile(runId)

        assertThat(report.failedChecks).containsExactly(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG)
    }

    @Test
    fun `detects a posting whose debit amount differs from the transfer`() {
        val (runId, transferId) = runWithOnePosting()
        val second = transfer(runId, from = 2, to = 3, amountCents = 50)
        ledger.apply(second)
        // Shift 10 between the two postings' debits: each account and every total still agree.
        jdbc.sql("UPDATE journal_entries SET amount_cents = amount_cents - 10 WHERE run_id = :runId AND transfer_id = :t AND amount_cents < 0")
            .param("runId", runId).param("t", transferId).update()
        jdbc.sql("UPDATE journal_entries SET amount_cents = amount_cents + 10 WHERE run_id = :runId AND transfer_id = :t AND amount_cents < 0")
            .param("runId", runId).param("t", second.transferId).update()
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents - 10 WHERE run_id = :runId AND account_no = 1")
            .param("runId", runId).update()
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 10 WHERE run_id = :runId AND account_no = 2")
            .param("runId", runId).update()

        val report = runs.reconcile(runId)

        assertThat(report.failedChecks).containsExactly(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG)
    }

    @Test
    fun `detects a drifted balance on an account with no journal rows`() {
        val (runId, _) = runWithOnePosting()
        // Account 3 took no part in the posting: its check sums zero journal rows.
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 1 WHERE run_id = :runId AND account_no = 3")
            .param("runId", runId).update()

        val report = runs.reconcile(runId)

        assertThat(report.failedChecks).containsExactlyInAnyOrder(
            ReconciliationCheck.TOTAL_BALANCE_CONSERVED,
            ReconciliationCheck.EVERY_ACCOUNT_MATCHES_JOURNAL,
        )
    }

    @Test
    fun `detects a balance that drifted from its journal`() {
        val (runId, _) = runWithOnePosting()
        jdbc.sql("UPDATE accounts SET balance_cents = balance_cents + 1 WHERE run_id = :runId AND account_no = 1")
            .param("runId", runId).update()

        val report = runs.reconcile(runId)

        assertThat(report.failedChecks).containsExactlyInAnyOrder(
            ReconciliationCheck.TOTAL_BALANCE_CONSERVED,
            ReconciliationCheck.EVERY_ACCOUNT_MATCHES_JOURNAL,
        )
    }
}
