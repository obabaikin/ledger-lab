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
    fun `skews traffic towards hot accounts`() {
        val profile = LoadProfile(UUID.randomUUID(), accountCount = 100, blockedAccounts = 2, hotAccounts = 5, seed = 7)

        val commands = List(20_000) { profile.next() }
        val toHot = commands.count { it.toAccount in 1..5 } / commands.size.toDouble()

        assertThat(toHot).isBetween(0.30, 0.40)
        assertThat(commands.filter { it.amountCents != profile.oversizedAmountCents })
            .allSatisfy { assertThat(it.amountCents).isBetween(1, 2_000) }
    }

    @Test
    fun `invalid transfers come by count, exactly the share of a run and never both on one transfer`() {
        val profile = LoadProfile(UUID.randomUUID(), accountCount = 100, blockedAccounts = 2, hotAccounts = 5, seed = 3)

        val commands = List(1_000) { profile.next() }
        val oversized = commands.withIndex().filter { it.value.amountCents == profile.oversizedAmountCents }.map { it.index + 1 }
        val toBlocked = commands.withIndex().filter { it.value.toAccount in 99..100 }.map { it.index + 1 }

        assertThat(oversized).containsExactly(200, 400, 600, 800, 1_000)
        assertThat(toBlocked).containsExactly(100, 300, 500, 700, 900)
    }

    @Test
    fun `a single hot account that pays does not pay itself`() {
        val profile = LoadProfile(UUID.randomUUID(), accountCount = 3, blockedAccounts = 0, hotAccounts = 1, hotShare = 1.0, seed = 5)

        val commands = List(1_000) { profile.next() }

        assertThat(commands).allSatisfy { assertThat(it.toAccount).isNotEqualTo(it.fromAccount) }
        assertThat(commands.filter { it.fromAccount == 1 }).isNotEmpty()
    }
}
