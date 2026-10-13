package com.ledgerlab.reporting

import com.ledgerlab.reporting.persistence.AcceptedTransfer
import com.ledgerlab.reporting.persistence.RunStatsRepository
import com.ledgerlab.reporting.persistence.TransferOutcomeRow
import com.ledgerlab.reporting.persistence.TransferStatsRepository
import com.ledgerlab.common.TransferOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConcurrentWritersTest : IntegrationTest() {

    @Autowired lateinit var transfers: TransferStatsRepository
    @Autowired lateinit var stats: RunStatsRepository
    @Autowired lateinit var tx: TransactionTemplate

    @Test
    fun `commands and results of the same transfers written at once in opposite orders neither deadlock nor overwrite each other`() {
        val runId = UUID.randomUUID()
        val now = Instant.now()
        val ids = (1..ROWS).map { UUID.randomUUID() }
        val accepted = ids.map { AcceptedTransfer(it, runId, 10, now) }
        val outcomes = ids.reversed().map { TransferOutcomeRow(it, runId, TransferOutcome.POSTED.name, null, now) }
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)

        try {
            repeat(ROUNDS) {
                val writes = listOf(
                    pool.submit { barrier.await(); tx.executeWithoutResult { transfers.upsertAccepted(accepted) } },
                    pool.submit { barrier.await(); tx.executeWithoutResult { transfers.upsertOutcomes(outcomes) } },
                )
                writes.forEach { it.get(30, TimeUnit.SECONDS) }
            }
        } finally {
            pool.shutdownNow()
        }

        val totals = stats.snapshot(runId).totals
        assertThat(totals.accepted).isEqualTo(ROWS.toLong())
        assertThat(totals.posted).isEqualTo(ROWS.toLong())
        assertThat(totals.postedAmountCents).isEqualTo(10L * ROWS)
    }

    private companion object {
        const val ROWS = 400
        const val ROUNDS = 10
    }
}
