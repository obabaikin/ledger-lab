package com.ledgerlab.gateway

import com.ledgerlab.gateway.api.TransferWarmUp
import com.ledgerlab.gateway.persistence.RunPeriodRepository
import com.ledgerlab.gateway.persistence.TransferRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.validation.Validator
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.any
import org.mockito.AdditionalAnswers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@TestPropertySource(properties = ["gateway.warm-up.rounds=1000000", "gateway.warm-up.duration=1s", "gateway.warm-up.max-duration=4s"])
@ExtendWith(OutputCaptureExtension::class)
class WarmUpTest : IntegrationTest() {

    @Autowired lateinit var warmUp: TransferWarmUp
    @Autowired lateinit var availability: ApplicationAvailability
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var registry: MeterRegistry
    @Autowired lateinit var transfers: TransferRepository
    @Autowired lateinit var periods: RunPeriodRepository
    @Autowired lateinit var transactions: PlatformTransactionManager
    @Autowired lateinit var validator: Validator
    @Autowired lateinit var events: ApplicationEventPublisher

    private fun transferRows() = jdbc.sql("SELECT count(*) FROM transfers").query(Long::class.java).single()

    private fun accepted() = registry.counter("gateway.transfers", "outcome", "accepted").count()

    private fun warm() = registry.get(TransferWarmUp.WARM).gauge().value()

    private fun awaitWarm() = await().atMost(Duration.ofSeconds(10)).until { warm() == 1.0 }

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
        assertThat(output.out).contains("Warm-up reached its limit of PT4S")
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

    // The stub goes on a mock of the test's own: Mockito stubbing is not thread-safe, and the outbox publisher calls the shared bean meanwhile.
    private fun warmUpWithInsertAfter(pause: () -> Unit): Pair<TransferWarmUp, MeterRegistry> {
        val real = AdditionalAnswers.delegatesTo<Any>(transfers)
        val stubbed = mock(TransferRepository::class.java, real)
        doAnswer { pause(); real.answer(it) }.`when`(stubbed).insertIfAbsent(any(UUID::class.java) ?: UUID.randomUUID(), any(UUID::class.java) ?: UUID.randomUUID(), anyInt(), anyInt(), anyLong())
        val ownRegistry = SimpleMeterRegistry()
        return TransferWarmUp(stubbed, periods, transactions, objectMapper, validator, events, props, ownRegistry) to ownRegistry
    }

    private fun awaitWarm(registry: MeterRegistry) =
        await().atMost(Duration.ofSeconds(10)).until { registry.get(TransferWarmUp.WARM).gauge().value() == 1.0 }

    @Test
    fun `a round that ends just past the long limit counts as the limit reached, not as a stuck round`(output: CapturedOutput) {
        val (slowWarmUp, ownRegistry) = warmUpWithInsertAfter { Thread.sleep(100) }

        slowWarmUp.warmUp()
        awaitWarm(ownRegistry)

        assertThat(output.out).contains("Warm-up reached its limit of PT4S").doesNotContain("did not finish a round")
    }

    @Test
    fun `a round stuck on the database holds back neither the traffic nor, past the long limit, the runs`(output: CapturedOutput) {
        val stuck = CountDownLatch(1)
        val (stuckWarmUp, ownRegistry) = warmUpWithInsertAfter { stuck.await() }
        try {
            val started = System.nanoTime()
            stuckWarmUp.warmUp()

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3))
            assertThat(availability.readinessState).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC)
            awaitWarm(ownRegistry)
            assertThat(output.out).contains("Warm-up did not finish a round within its limit of PT4S")
        } finally {
            stuck.countDown()
        }
    }
}
