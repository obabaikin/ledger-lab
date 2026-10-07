package com.ledgerlab.ledger

import com.ledgerlab.ledger.domain.TransferResult
import com.ledgerlab.ledger.service.LedgerService
import com.ledgerlab.ledger.service.RunService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class LedgerConcurrencyTest : IntegrationTest() {

    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var runs: RunService

    @Test
    fun `concurrent transfers between a few hot accounts never deadlock and always reconcile`() {
        val runId = UUID.randomUUID()
        val accountCount = 5
        runs.openAccounts(runId, setup(accountCount = accountCount, openingBalanceCents = 10_000))
        val random = Random(42)
        val commands = List(1_000) {
            val from = random.nextInt(1, accountCount + 1)
            val to = generateSequence { random.nextInt(1, accountCount + 1) }.first { it != from }
            transfer(runId, from, to, amountCents = random.nextLong(1, 500))
        }

        val pool = Executors.newFixedThreadPool(16)
        val results = try {
            pool.invokeAll(commands.map { Callable { ledger.apply(it) } }).map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(results).allMatch { it is TransferResult.Posted || it is TransferResult.Rejected }
        val report = runs.reconcile(runId)
        assertThat(report.failedChecks).isEmpty()
        assertThat(report.postedTransfers + report.rejectedTransfers).isEqualTo(commands.size.toLong())
        assertThat(report.balanceTotalCents).isEqualTo(accountCount * 10_000L)
    }

    @Test
    fun `concurrent deliveries of the same transfer post it exactly once`() {
        val runId = UUID.randomUUID()
        runs.openAccounts(runId, setup(accountCount = 2, openingBalanceCents = 1_000))
        val command = transfer(runId, from = 1, to = 2, amountCents = 100)

        val pool = Executors.newFixedThreadPool(16)
        val results = try {
            pool.invokeAll(List(16) { Callable { ledger.apply(command) } }).map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(results.count { it == TransferResult.Posted }).isEqualTo(1)
        assertThat(results.count { it == TransferResult.AlreadyProcessed }).isEqualTo(15)
        assertThat(runs.listAccounts(runId).first { it.accountNo == 1 }.balanceCents).isEqualTo(900)
        assertThat(runs.reconcile(runId).failedChecks).isEmpty()
    }
}
