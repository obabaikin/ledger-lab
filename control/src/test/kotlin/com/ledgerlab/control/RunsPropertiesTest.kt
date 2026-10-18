package com.ledgerlab.control

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class RunsPropertiesTest {
    @Test
    fun `the default TPS must be one of the options`() {
        assertThatThrownBy { runs(tpsOptions = listOf(50, 100, 300), defaultTps = 500) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("default-tps 500")
    }

    @Test
    fun `a default TPS among the options is accepted`() {
        assertThatCode { runs(tpsOptions = listOf(50, 100, 300), defaultTps = 100) }.doesNotThrowAnyException()
    }

    private fun runs(tpsOptions: List<Int>, defaultTps: Int) = ControlProperties.Runs(
        maxActive = 3,
        maxQueued = 10,
        queueTimeout = Duration.ofMinutes(5),
        tpsOptions = tpsOptions,
        defaultTps = defaultTps,
        durationOptions = listOf(Duration.ofSeconds(30)),
        setupTimeout = Duration.ofSeconds(30),
        runningGrace = Duration.ofSeconds(60),
        drainStallTimeout = Duration.ofSeconds(60),
        drainTimeout = Duration.ofMinutes(3),
        accounts = ControlProperties.Accounts(count = 100, openingBalanceCents = 1_000_000, blocked = 2, dailyLimitCents = 5_000_000),
        maxTotalTps = 500,
    )
}
