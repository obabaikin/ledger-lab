package com.ledgerlab.generator

import com.ledgerlab.common.TransferCommand
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

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
    val amountRangeCents: LongRange = 100L..5_000L,
    /** Amount that no account can cover, to provoke INSUFFICIENT_FUNDS. */
    val oversizedAmountCents: Long = 1_000_000_000L,
    val oversizedShare: Double = 0.01,
    val blockedShare: Double = 0.01,
    val seed: Long = System.nanoTime(),
) {
    private val random = Random(seed)
    private val activeAccounts = 1..(accountCount - blockedAccounts)
    private val blocked = (accountCount - blockedAccounts + 1)..accountCount

    init {
        require(accountCount - blockedAccounts >= 2) { "at least two active accounts are needed" }
        require(blockedAccounts >= 0)
        require(hotAccounts in 0..activeAccounts.last)
    }

    // kotlin.random.Random is not thread-safe and next() is called from many coroutines.
    @Synchronized
    fun next(): TransferCommand {
        val from = activeAccounts.random(random)
        val roll = random.nextDouble()
        val to = when {
            roll < blockedShare && blockedAccounts > 0 -> blocked.random(random)
            roll < blockedShare + hotShare && hotAccounts > 0 -> (1..hotAccounts).random(random)
            else -> activeAccounts.random(random)
        }.let { if (it == from) (if (from == activeAccounts.last) 1 else from + 1) else it }
        val amount = if (random.nextDouble() < oversizedShare) oversizedAmountCents else amountRangeCents.random(random)
        return TransferCommand(runId, UUID.randomUUID(), from, to, amount, Instant.now())
    }
}
