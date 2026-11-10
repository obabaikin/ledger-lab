package com.ledgerlab.ledger

import com.ledgerlab.common.RejectReason
import com.ledgerlab.ledger.domain.TransferResult
import com.ledgerlab.ledger.service.DeferredCreditBatch
import com.ledgerlab.ledger.service.LedgerService
import com.ledgerlab.ledger.service.RunService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.awaitility.Awaitility.await
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import javax.sql.DataSource
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// The schedule would apply credits between the steps of a test; here the tests apply them themselves.
@TestPropertySource(properties = ["ledger.deferred-credits.apply-interval=1h"])
class DeferredCreditTest : IntegrationTest() {

    @Autowired lateinit var ledger: LedgerService
    @Autowired lateinit var runs: RunService
    @Autowired lateinit var credits: DeferredCreditBatch
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun `a pending credit counts in reconciliation and reaches the balance once applied`() {
        val runId = openRun()

        assertThat(ledger.applyBatch(listOf(transfer(runId, 2, 1, 100), transfer(runId, 2, 3, 50))))
            .containsOnly(TransferResult.Posted)

        assertThat(balances(runId)).containsExactlyInAnyOrderEntriesOf(mapOf(1 to 1_000L, 2 to 850L, 3 to 1_000L))
        assertThat(runs.reconcile(runId).failedChecks).isEmpty()
        assertThat(runs.reconcile(runId).balanceTotalCents).isEqualTo(3_000)

        applyAll()
        applyAll()

        assertThat(balances(runId)).containsExactlyInAnyOrderEntriesOf(mapOf(1 to 1_100L, 2 to 850L, 3 to 1_050L))
        assertThat(pendingCredits(runId)).isZero()
        assertThat(runs.reconcile(runId).failedChecks).isEmpty()
    }

    @Test
    fun `a receiver that also sends in the batch gets its credit once`() {
        val runId = openRun()

        val results = ledger.applyBatch(
            listOf(transfer(runId, 2, 1, 300), transfer(runId, 1, 3, 200), transfer(runId, 3, 1, 100)),
        )

        assertThat(results).containsOnly(TransferResult.Posted)
        assertThat(balances(runId)[1]).isEqualTo(800)
        applyAll()
        assertThat(balances(runId)).containsExactlyInAnyOrderEntriesOf(mapOf(1 to 1_200L, 2 to 700L, 3 to 1_100L))
        assertThat(runs.reconcile(runId).failedChecks).isEmpty()
    }

    @Test
    fun `a sender cannot spend a credit that is not applied yet`() {
        val runId = openRun()

        ledger.applyOne(transfer(runId, 2, 1, 500))

        assertThat(ledger.applyOne(transfer(runId, 1, 3, 1_200)))
            .isEqualTo(TransferResult.Rejected(RejectReason.INSUFFICIENT_FUNDS))
        applyAll()
        assertThat(ledger.applyOne(transfer(runId, 1, 3, 1_200))).isEqualTo(TransferResult.Posted)
    }

    @Test
    fun `two appliers at once add each credit once`() {
        val runId = openRun(accounts = 6, openingBalanceCents = 100_000)
        repeat(200) { i -> ledger.applyOne(transfer(runId, from = 3 + i % 4, to = 1 + i % 2, amountCents = 10)) }

        val pool = Executors.newFixedThreadPool(8)
        try {
            pool.invokeAll(List(8) { Callable { applyAll() } }).forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(balances(runId).filterKeys { it <= 2 }).containsExactlyInAnyOrderEntriesOf(mapOf(1 to 101_000L, 2 to 101_000L))
        assertThat(pendingCredits(runId)).isZero()
        assertThat(runs.reconcile(runId).failedChecks).isEmpty()
    }

    @Test
    fun `the applier and a batch taking the same accounts wait for each other instead of deadlocking`() {
        val accounts = 200
        val runId = openRun(accounts = accounts + 1, openingBalanceCents = 100_000)
        val payer = accounts + 1
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(DEADLOCK_ROUNDS) {
                // One by one: the applier takes credits in the order they were recorded, here against the order of the lock.
                (accounts downTo 1).forEach { ledger.applyOne(transfer(runId, from = payer, to = it, amountCents = 1)) }
                val start = CountDownLatch(1)
                val applying = pool.submit { start.await(); applyAll() }
                val posting = pool.submit(Callable { start.await(); ledger.applyBatch(List(accounts) { transfer(runId, from = it + 1, to = payer, amountCents = 1) }) })
                start.countDown()

                applying.get(30, TimeUnit.SECONDS)
                assertThat(posting.get(30, TimeUnit.SECONDS)).containsOnly(TransferResult.Posted)
            }
        } finally {
            pool.shutdownNow()
        }
        applyAll()
        assertThat(pendingCredits(runId)).isZero()
        assertThat(runs.reconcile(runId).failedChecks).isEmpty()
        // Thousands of results would keep the publisher and the readers of the results topic busy for the tests after this one.
        runs.deleteRun(runId)
    }

    @Test
    fun `deleting a run deletes its pending credits`() {
        val runId = openRun()
        ledger.applyOne(transfer(runId, 2, 1, 100))

        runs.deleteRun(runId)

        assertThat(pendingCredits(runId)).isZero()
    }

    @Test
    fun `deleting a run while the applier holds its credits waits for the applier instead of deadlocking`() {
        val runId = openRun()
        ledger.applyOne(transfer(runId, 2, 1, 100))
        dataSource.connection.use { applier ->
            applier.autoCommit = false
            applier.prepareStatement("DELETE FROM deferred_credits WHERE run_id = ?").use { it.setObject(1, runId); it.executeUpdate() }
            val waitingBefore = waitingLocks()

            val deleting = CompletableFuture.supplyAsync { runs.deleteRun(runId) }
            await().atMost(Duration.ofSeconds(5)).until { waitingLocks() > waitingBefore }
            applier.prepareStatement(
                "SELECT 1 FROM accounts WHERE run_id = ? AND account_no = 1 FOR NO KEY UPDATE",
            ).use { it.setObject(1, runId); it.executeQuery().close() }
            applier.commit()

            assertThat(deleting.get(10, TimeUnit.SECONDS)).isTrue()
        }
        assertThat(runs.listAccounts(runId)).isEmpty()
    }

    private fun waitingLocks(): Int =
        jdbc.sql("SELECT count(*) FROM pg_locks WHERE NOT granted").query(Int::class.java).single()

    private fun openRun(accounts: Int = 3, openingBalanceCents: Long = 1_000): UUID {
        val runId = UUID.randomUUID()
        runs.openAccounts(runId, setup(accountCount = accounts, openingBalanceCents = openingBalanceCents))
        return runId
    }

    private fun applyAll() {
        while (credits.applyNext() > 0) Unit
    }

    private fun balances(runId: UUID) = runs.listAccounts(runId).associate { it.accountNo to it.balanceCents }

    private companion object {
        const val DEADLOCK_ROUNDS = 20
    }
}
