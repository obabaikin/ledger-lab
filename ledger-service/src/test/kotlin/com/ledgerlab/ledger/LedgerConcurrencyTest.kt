package com.ledgerlab.ledger

import com.ledgerlab.common.RejectReason
import com.ledgerlab.ledger.domain.AccountKey
import com.ledgerlab.ledger.domain.TransferResult
import com.ledgerlab.ledger.persistence.AccountRepository
import com.ledgerlab.ledger.service.LedgerService
import com.ledgerlab.ledger.service.RunService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class LedgerConcurrencyTest : IntegrationTest() {

    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var runs: RunService
    @Autowired lateinit var accounts: AccountRepository
    @Autowired lateinit var tx: TransactionTemplate

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
            pool.invokeAll(commands.map { Callable { ledger.applyOne(it) } }).map { it.get(30, TimeUnit.SECONDS) }
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
            pool.invokeAll(List(16) { Callable { ledger.applyOne(command) } }).map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(results.count { it == TransferResult.Posted }).isEqualTo(1)
        assertThat(results.count { it == TransferResult.AlreadyProcessed }).isEqualTo(15)
        assertThat(runs.listAccounts(runId).first { it.accountNo == 1 }.balanceCents).isEqualTo(900)
        assertThat(runs.reconcile(runId).failedChecks).isEmpty()
    }

    @Test
    fun `transactions locking overlapping accounts given in different orders all commit without a deadlock`() {
        val runId = UUID.randomUUID()
        runs.openAccounts(runId, setup(accountCount = LOCKED_ACCOUNTS))
        val keys = { count: Int -> List(count) { AccountKey(runId, it + 1) } }
        val pool = Executors.newFixedThreadPool(3)
        try {
            repeat(LOCK_ROUNDS) {
                val start = CountDownLatch(1)
                val locked = listOf(keys(LOCKED_ACCOUNTS), keys(LOCKED_ACCOUNTS / 7).reversed(), keys(LOCKED_ACCOUNTS / 3).shuffled())
                    .map { order -> pool.submit(Callable { start.await(); tx.execute { lockAndRewrite(order) } }) }
                    .also { start.countDown() }
                    .map { it.get(30, TimeUnit.SECONDS) }

                assertThat(locked).containsExactly(LOCKED_ACCOUNTS, LOCKED_ACCOUNTS / 7, LOCKED_ACCOUNTS / 3)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Rewriting the rows moves them in the table, as the balance updates of real batches do between two locks. */
    private fun lockAndRewrite(order: List<AccountKey>): Int =
        accounts.lockAll(LinkedHashSet(order)).also(accounts::updateBalances).size

    @Test
    fun `concurrent deliveries of a transfer that locks no account report its rejection once`() {
        val runId = UUID.randomUUID()
        runs.openAccounts(runId, setup(accountCount = 2))
        val pool = Executors.newFixedThreadPool(16)
        try {
            repeat(ROUNDS) {
                val command = transfer(runId, from = 1, to = 2, amountCents = 0)
                val results = pool.invokeAll(List(16) { Callable { ledger.applyOne(command) } }).map { it.get(30, TimeUnit.SECONDS) }

                assertThat(results.count { it == TransferResult.Rejected(RejectReason.INVALID_AMOUNT) }).isEqualTo(1)
                assertThat(results.count { it == TransferResult.AlreadyProcessed }).isEqualTo(15)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val LOCKED_ACCOUNTS = 2_000
        const val LOCK_ROUNDS = 40
        const val ROUNDS = 10
    }
}
