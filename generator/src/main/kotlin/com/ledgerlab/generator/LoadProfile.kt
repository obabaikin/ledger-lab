package com.ledgerlab.generator

import java.util.UUID
import kotlin.random.Random

/** A transfer the simulated client is about to make; its id is the idempotency key. */
data class PlannedTransfer(
    val runId: UUID,
    val transferId: UUID,
    val fromAccount: Int,
    val toAccount: Int,
    val amountCents: Long,
)

/**
 * Shapes the traffic like a retail bank: most payments go to a few merchants (hot accounts),
 * a small share is deliberately invalid so that rejections show up in the results.
 */
data class LoadProfile(
    val runId: UUID,
    val accountCount: Int,
    /** The last [blockedAccounts] account numbers are blocked (ledger convention). */
    val blockedAccounts: Int,
    /** Account numbers 1..hotAccounts receive [hotShare] of all payments. */
    val hotAccounts: Int,
    val hotShare: Double = 0.3,
    val amountRangeCents: LongRange = 1L..2_000L,
    /**
     * Amount that no account can cover, to provoke INSUFFICIENT_FUNDS: 10 000 000 EUR, above a 1 000 000 EUR opening
     * balance plus what a merchant collects in an hour at 300 TPS (about 615 000 EUR), so it is never posted.
     */
    val oversizedAmountCents: Long = 1_000_000_000L,
    val oversizedShare: Double = 0.005,
    val blockedShare: Double = 0.005,
    val seed: Long = System.nanoTime(),
) {
    private val random = Random(seed)
    private val activeAccounts = 1..(accountCount - blockedAccounts)
    private val blocked = (accountCount - blockedAccounts + 1)..accountCount
    private val oversizedPpm = toPpm(oversizedShare)
    private val blockedPpm = toPpm(blockedShare)
    private var count = 0L

    init {
        require(accountCount - blockedAccounts >= 2) { "at least two active accounts are needed" }
        require(blockedAccounts >= 0)
        require(hotAccounts in 0..activeAccounts.last)
        require(oversizedShare in 0.0..0.5 && blockedShare in 0.0..0.5) { "invalid shares must be at most 0.5 each" }
    }

    // kotlin.random.Random is not thread-safe and next() is called from many coroutines.
    @Synchronized
    fun next(): PlannedTransfer {
        val n = ++count
        val from = activeAccounts.random(random)
        val to = when {
            // Half a period apart, so with equal shares a transfer is never picked twice.
            picked(n, blockedPpm, PPM / 2) && blockedAccounts > 0 -> blocked.random(random)
            random.nextDouble() < hotShare && hotAccounts > 0 -> anyBut(1..hotAccounts, from) ?: anyBut(activeAccounts, from)!!
            else -> anyBut(activeAccounts, from)!!
        }
        val amount = if (picked(n, oversizedPpm, 0)) oversizedAmountCents else amountRangeCents.random(random)
        return PlannedTransfer(runId, UUID.randomUUID(), from, to, amount)
    }

    /** Integer arithmetic, so that exactly floor(n * share) of the first n transfers are picked. */
    private fun picked(n: Long, ppm: Long, phase: Long): Boolean =
        (n * ppm + phase) / PPM > ((n - 1) * ppm + phase) / PPM

    private fun anyBut(range: IntRange, excluded: Int): Int? {
        val size = range.last - range.first + 1 - (if (excluded in range) 1 else 0)
        if (size <= 0) return null
        val pick = range.first + random.nextInt(size)
        return if (excluded in range && pick >= excluded) pick + 1 else pick
    }

    private companion object {
        const val PPM = 1_000_000L

        fun toPpm(share: Double): Long = Math.round(share * PPM)
    }
}
