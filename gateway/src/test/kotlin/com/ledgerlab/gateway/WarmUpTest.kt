package com.ledgerlab.gateway

import com.ledgerlab.gateway.api.TransferWarmUp
import com.ledgerlab.gateway.persistence.TransferRepository
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@TestPropertySource(properties = ["gateway.warm-up.rounds=1000000", "gateway.warm-up.duration=1s", "gateway.warm-up.max-duration=2s"])
@ExtendWith(OutputCaptureExtension::class)
class WarmUpTest : IntegrationTest() {

    @Autowired lateinit var warmUp: TransferWarmUp
    @Autowired lateinit var availability: ApplicationAvailability
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var registry: MeterRegistry
    @MockitoSpyBean lateinit var transfers: TransferRepository

    private fun transferRows() = jdbc.sql("SELECT count(*) FROM transfers").query(Long::class.java).single()

    private fun accepted() = registry.counter("gateway.transfers", "outcome", "accepted").count()

    private fun warm() = registry.get(TransferWarmUp.WARM).gauge().value()

    private fun awaitWarm() = await().atMost(Duration.ofSeconds(5)).until { warm() == 1.0 }

    /** The context's own warm-up at start goes on behind it up to the long limit; a test's warm-up must not overlap it. */
    @BeforeEach
    fun earlierWarmUpDone() = awaitWarm()

    @Test
    fun `the gateway refuses traffic while it warms up, takes it at the short limit and reports itself warm at the long one`(output: CapturedOutput) {
        Executors.newSingleThreadExecutor().use { pool ->
            val warming = pool.submit { warmUp.warmUp() }
            await().atMost(Duration.ofSeconds(5)).until { availability.readinessState == ReadinessState.REFUSING_TRAFFIC }
            await().during(Duration.ofMillis(500)).atMost(Duration.ofMillis(900)).until { availability.readinessState == ReadinessState.REFUSING_TRAFFIC }

            warming.get()
        }

        assertThat(availability.readinessState).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC)
        assertThat(warm()).isEqualTo(0.0)
        awaitWarm()
        assertThat(output.out).contains("Warm-up reached its limit of PT2S")
    }

    @Test
    fun `the warm-up writes no transfer and counts none`() {
        val rows = transferRows()
        val counted = accepted()

        warmUp.warmUp()
        awaitWarm()

        assertThat(transferRows()).isEqualTo(rows)
        assertThat(accepted()).isEqualTo(counted)
    }

    @Test
    fun `a round that ends just past the long limit counts as the limit reached, not as a stuck round`(output: CapturedOutput) {
        doAnswer { Thread.sleep(300); it.callRealMethod() }
            .`when`(transfers).insertIfAbsent(any(UUID::class.java) ?: UUID.randomUUID(), any(UUID::class.java) ?: UUID.randomUUID(), anyInt(), anyInt(), anyLong())

        warmUp.warmUp()
        awaitWarm()

        assertThat(output.out).contains("Warm-up reached its limit of PT2S").doesNotContain("did not finish a round")
    }

    @Test
    fun `a round stuck on the database holds back neither the traffic nor, past the long limit, the runs`(output: CapturedOutput) {
        val stuck = CountDownLatch(1)
        doAnswer { stuck.await(); it.callRealMethod() }
            .`when`(transfers).insertIfAbsent(any(UUID::class.java) ?: UUID.randomUUID(), any(UUID::class.java) ?: UUID.randomUUID(), anyInt(), anyInt(), anyLong())
        try {
            val started = System.nanoTime()
            warmUp.warmUp()

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2))
            assertThat(availability.readinessState).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC)
            awaitWarm()
            assertThat(output.out).contains("Warm-up did not finish a round within its limit of PT2S")
        } finally {
            stuck.countDown()
        }
    }
}
