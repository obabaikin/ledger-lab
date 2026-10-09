package com.ledgerlab.generator

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class LoadProfileTest {

    @Test
    fun `never sends from a blocked account or to the same account`() {
        val profile = LoadProfile(UUID.randomUUID(), accountCount = 50, blockedAccounts = 5, hotAccounts = 3, seed = 1)

        val commands = List(10_000) { profile.next() }

        assertThat(commands).allSatisfy { assertThat(it.fromAccount).isBetween(1, 45) }
        assertThat(commands).allSatisfy { assertThat(it.toAccount).isNotEqualTo(it.fromAccount) }
        assertThat(commands).allSatisfy { assertThat(it.amountCents).isPositive() }
    }

    @Test
    fun `needs at least two active accounts`() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            LoadProfile(UUID.randomUUID(), accountCount = 2, blockedAccounts = 1, hotAccounts = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `skews traffic towards hot accounts and injects some invalid transfers`() {
        val profile = LoadProfile(UUID.randomUUID(), accountCount = 100, blockedAccounts = 2, hotAccounts = 5, seed = 7)

        val commands = List(20_000) { profile.next() }
        val toHot = commands.count { it.toAccount in 1..5 } / commands.size.toDouble()
        val toBlocked = commands.count { it.toAccount in 99..100 } / commands.size.toDouble()
        val oversized = commands.count { it.amountCents >= 1_000_000_000L } / commands.size.toDouble()

        assertThat(toHot).isBetween(0.30, 0.40)
        assertThat(toBlocked).isBetween(0.005, 0.02)
        assertThat(oversized).isBetween(0.005, 0.02)
    }
}
