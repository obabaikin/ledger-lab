package com.ledgerlab.ledger

import com.ledgerlab.common.RejectReason
import com.ledgerlab.ledger.domain.TransferResult
import com.ledgerlab.ledger.service.LedgerService
import com.ledgerlab.ledger.service.RunService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class LedgerServiceTest : IntegrationTest() {

    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var runs: RunService

    @Test
    fun `posts a transfer as two journal entries and moves the balance`() {
        val runId = UUID.randomUUID()
        runs.openAccounts(runId, setup(accountCount = 2, openingBalanceCents = 1_000))

        val result = ledger.applyOne(transfer(runId, from = 1, to = 2, amountCents = 250))

        assertThat(result).isEqualTo(TransferResult.Posted)
        awaitCreditsApplied(runId)
        val balances = runs.listAccounts(runId).associate { it.accountNo to it.balanceCents }
        assertThat(balances).containsExactlyInAnyOrderEntriesOf(mapOf(1 to 750L, 2 to 1_250L))
        val report = runs.reconcile(runId)
        assertThat(report.balanced).isTrue()
        assertThat(report.postedTransfers).isEqualTo(1)
        assertThat(report.journalEntries).isEqualTo(2)
    }

    @Test
    fun `ignores a second delivery of the same transfer`() {
        val runId = UUID.randomUUID()
        runs.openAccounts(runId, setup(accountCount = 2, openingBalanceCents = 1_000))
        val command = transfer(runId, from = 1, to = 2, amountCents = 100)

        assertThat(ledger.applyOne(command)).isEqualTo(TransferResult.Posted)
        assertThat(ledger.applyOne(command)).isEqualTo(TransferResult.AlreadyProcessed)

        assertThat(runs.listAccounts(runId).first { it.accountNo == 1 }.balanceCents).isEqualTo(900)
        assertThat(runs.reconcile(runId).postedTransfers).isEqualTo(1)
    }

    @Test
    fun `rejects insufficient funds without touching balances`() {
        val runId = UUID.randomUUID()
        runs.openAccounts(runId, setup(accountCount = 2, openingBalanceCents = 100))

        val result = ledger.applyOne(transfer(runId, from = 1, to = 2, amountCents = 101))

        assertThat(result).isEqualTo(TransferResult.Rejected(RejectReason.INSUFFICIENT_FUNDS))
        assertThat(runs.listAccounts(runId).map { it.balanceCents }).containsOnly(100L)
        val report = runs.reconcile(runId)
        assertThat(report.balanced).isTrue()
        assertThat(report.rejectionsByReason).containsEntry(RejectReason.INSUFFICIENT_FUNDS, 1L)
    }

    @Test
    fun `rejects transfers from or to a blocked account`() {
        val runId = UUID.randomUUID()
        // accounts 1..2 active, 3 blocked
        runs.openAccounts(runId, setup(accountCount = 3, openingBalanceCents = 1_000, blockedAccounts = 1))

        assertThat(ledger.applyOne(transfer(runId, from = 3, to = 1, amountCents = 10)))
            .isEqualTo(TransferResult.Rejected(RejectReason.BLOCKED_ACCOUNT))
        assertThat(ledger.applyOne(transfer(runId, from = 1, to = 3, amountCents = 10)))
            .isEqualTo(TransferResult.Rejected(RejectReason.BLOCKED_ACCOUNT))
        assertThat(ledger.applyOne(transfer(runId, from = 1, to = 2, amountCents = 10)))
            .isEqualTo(TransferResult.Posted)
    }

    @Test
    fun `rejects when the daily limit would be exceeded`() {
        val runId = UUID.randomUUID()
        runs.openAccounts(runId, setup(accountCount = 2, openingBalanceCents = 10_000, dailyLimitCents = 300))

        assertThat(ledger.applyOne(transfer(runId, from = 1, to = 2, amountCents = 200))).isEqualTo(TransferResult.Posted)
        assertThat(ledger.applyOne(transfer(runId, from = 1, to = 2, amountCents = 101)))
            .isEqualTo(TransferResult.Rejected(RejectReason.DAILY_LIMIT_EXCEEDED))
        assertThat(ledger.applyOne(transfer(runId, from = 1, to = 2, amountCents = 100))).isEqualTo(TransferResult.Posted)
    }

    @Test
    fun `rejects non-positive amounts instead of letting the database refuse them`() {
        val runId = UUID.randomUUID()
        runs.openAccounts(runId, setup(accountCount = 2))

        assertThat(ledger.applyOne(transfer(runId, from = 1, to = 2, amountCents = 0)))
            .isEqualTo(TransferResult.Rejected(RejectReason.INVALID_AMOUNT))
        assertThat(ledger.applyOne(transfer(runId, from = 1, to = 2, amountCents = -50)))
            .isEqualTo(TransferResult.Rejected(RejectReason.INVALID_AMOUNT))

        val report = runs.reconcile(runId)
        assertThat(report.balanced).isTrue()
        assertThat(report.rejectionsByReason).containsEntry(RejectReason.INVALID_AMOUNT, 2L)
    }

    @Test
    fun `rejects same-account and unknown-account transfers`() {
        val runId = UUID.randomUUID()
        runs.openAccounts(runId, setup(accountCount = 2))

        assertThat(ledger.applyOne(transfer(runId, from = 1, to = 1, amountCents = 1)))
            .isEqualTo(TransferResult.Rejected(RejectReason.SAME_ACCOUNT))
        assertThat(ledger.applyOne(transfer(runId, from = 1, to = 99, amountCents = 1)))
            .isEqualTo(TransferResult.Rejected(RejectReason.UNKNOWN_ACCOUNT))
    }

    @Test
    fun `a batch of transfers posts each with the outcome it would have alone`() {
        val runId = UUID.randomUUID()
        runs.openAccounts(runId, setup(accountCount = 3, openingBalanceCents = 100))
        val first = transfer(runId, from = 1, to = 2, amountCents = 80)
        val batch = listOf(
            first,
            transfer(runId, from = 1, to = 3, amountCents = 30),
            first,
            transfer(runId, from = 2, to = 3, amountCents = 100),
            transfer(runId, from = 1, to = 1, amountCents = 5),
        )

        assertThat(ledger.applyBatch(batch)).containsExactly(
            TransferResult.Posted,
            TransferResult.Rejected(RejectReason.INSUFFICIENT_FUNDS),
            TransferResult.AlreadyProcessed,
            TransferResult.Posted,
            TransferResult.Rejected(RejectReason.SAME_ACCOUNT),
        )

        awaitCreditsApplied(runId)
        val balances = runs.listAccounts(runId).associate { it.accountNo to it.balanceCents }
        assertThat(balances).containsExactlyInAnyOrderEntriesOf(mapOf(1 to 20L, 2 to 80L, 3 to 200L))
        assertThat(runs.reconcile(runId).failedChecks).isEmpty()
    }

    @Test
    fun `an unknown account rejects its transfer and posts the rest of the batch`() {
        val runId = UUID.randomUUID()
        runs.openAccounts(runId, setup(accountCount = 2, openingBalanceCents = 1_000))

        val results = ledger.applyBatch(
            listOf(transfer(runId, 1, 2, 100), transfer(runId, 1, 99, 100), transfer(runId, 99, 2, 100), transfer(runId, 2, 1, 50)),
        )

        assertThat(results).containsExactly(
            TransferResult.Posted,
            TransferResult.Rejected(RejectReason.UNKNOWN_ACCOUNT),
            TransferResult.Rejected(RejectReason.UNKNOWN_ACCOUNT),
            TransferResult.Posted,
        )
        assertThat(runs.reconcile(runId).postedTransfers).isEqualTo(2)
    }

    @Test
    fun `a redelivered batch posts nothing twice`() {
        val runId = UUID.randomUUID()
        runs.openAccounts(runId, setup(accountCount = 3, openingBalanceCents = 1_000))
        val batch = listOf(transfer(runId, 1, 2, 100), transfer(runId, 2, 3, 5_000), transfer(runId, 3, 1, 10))
        ledger.applyBatch(batch)

        assertThat(ledger.applyBatch(batch)).containsOnly(TransferResult.AlreadyProcessed)

        awaitCreditsApplied(runId)
        val report = runs.reconcile(runId)
        assertThat(report.postedTransfers).isEqualTo(2)
        assertThat(report.rejectedTransfers).isEqualTo(1)
        assertThat(runs.listAccounts(runId).associate { it.accountNo to it.balanceCents })
            .containsExactlyInAnyOrderEntriesOf(mapOf(1 to 910L, 2 to 1_100L, 3 to 990L))
        assertThat(report.failedChecks).isEmpty()
    }

    @Test
    fun `runs are isolated from each other`() {
        val runA = UUID.randomUUID()
        val runB = UUID.randomUUID()
        runs.openAccounts(runA, setup(accountCount = 2, openingBalanceCents = 1_000))
        runs.openAccounts(runB, setup(accountCount = 2, openingBalanceCents = 1_000))

        ledger.applyOne(transfer(runA, from = 1, to = 2, amountCents = 400))

        assertThat(runs.listAccounts(runB).map { it.balanceCents }).containsOnly(1_000L)
        assertThat(runs.reconcile(runA).postedTransfers).isEqualTo(1)
        assertThat(runs.reconcile(runB).postedTransfers).isEqualTo(0)
    }
}
