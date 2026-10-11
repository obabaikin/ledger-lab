package com.ledgerlab.control

import com.ledgerlab.control.ledger.LedgerReadiness
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.time.Duration

class LedgerReadinessTest {

    private val timeout = Duration.ofMillis(300)
    private val ledger = StubLedger()

    @AfterEach
    fun stop() = ledger.close()

    private fun readiness(baseUrl: String = ledger.baseUrl) = LedgerReadiness(com.ledgerlab.control.ControlProperties.Ledger(baseUrl, timeout))

    @Test
    fun `ready on 200`() {
        assertThat(readiness().isReady()).isTrue()
    }

    @Test
    fun `not ready on 503`() {
        ledger.status = 503

        assertThat(readiness().isReady()).isFalse()
    }

    @Test
    fun `not ready on a redirect`() {
        ledger.status = 302

        assertThat(readiness().isReady()).isFalse()
    }

    @Test
    fun `not ready when the answer takes longer than the timeout`() {
        ledger.delay = timeout.multipliedBy(3)

        val started = System.nanoTime()
        assertThat(readiness().isReady()).isFalse()
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(timeout.multipliedBy(3))
    }

    @Test
    fun `not ready when the connection is refused`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        assertThat(readiness("http://localhost:$closedPort").isReady()).isFalse()
    }
}
