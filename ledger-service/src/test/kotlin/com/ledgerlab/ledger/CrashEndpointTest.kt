package com.ledgerlab.ledger

import com.ledgerlab.ledger.chaos.CrashEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class CrashEndpointTest {

    @Test
    fun `crash endpoint answers 202 and halts after the delay`() {
        val halts = AtomicInteger()
        val endpoint = CrashEndpoint({ halts.incrementAndGet() }, HALT_DELAY)

        val answer = endpoint.crash()

        assertThat(answer.status).isEqualTo(202)
        Thread.sleep(HALT_DELAY.dividedBy(2))
        assertThat(halts.get()).isZero()
        await().atMost(HALT_DELAY.multipliedBy(5)).until { halts.get() == 1 }
    }

    private companion object {
        val HALT_DELAY: Duration = Duration.ofMillis(300)
    }
}
