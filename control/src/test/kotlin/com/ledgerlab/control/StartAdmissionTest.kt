package com.ledgerlab.control

import com.ledgerlab.control.api.ControlErrors
import com.ledgerlab.control.runs.RunRequest
import com.ledgerlab.control.runs.RunService
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@TestPropertySource(properties = ["control.runs.max-concurrent-starts=2", "control.runs.start-wait=50ms"])
class StartAdmissionTest : IntegrationTest() {

    @MockitoSpyBean lateinit var runService: RunService

    private val sample = RunRequest(UUID.randomUUID(), 100, 10)

    @Test
    fun `a start over the bound is refused with 503 busy and does not hold up the reads`() {
        val held = CountDownLatch(1)
        val inside = AtomicInteger()
        doAnswer { call ->
            inside.incrementAndGet()
            held.await()
            call.callRealMethod()
        }.`when`(runService).start(any(UUID::class.java) ?: UUID.randomUUID(), any(RunRequest::class.java) ?: sample)
        val sessions = List(3) { sessionOf(invite()) }

        Executors.newFixedThreadPool(2).use { pool ->
            val holding = sessions.take(2).map { session -> pool.submit<Int> { start(session).statusCode.value() } }
            await().atMost(Duration.ofSeconds(10)).until { inside.get() == 2 }

            val refused = start(sessions[2])
            assertThat(refused.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(refused.body!!["error"]).isEqualTo(ControlErrors.BUSY)
            assertThat(refused.headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1")
            assertThat(http.getForEntity("/api/sessions/${sessions[0]}/runs", Map::class.java).statusCode).isEqualTo(HttpStatus.OK)

            held.countDown()
            assertThat(holding.map { it.get() }).containsOnly(HttpStatus.ACCEPTED.value())
        }
        assertThat(start(sessions[2]).statusCode).isEqualTo(HttpStatus.ACCEPTED)
    }

    @Test
    fun `a start that fails gives its permit back`() {
        val session = sessionOf(invite())
        repeat(3) { assertThat(start(session, tps = 7).statusCode).isEqualTo(HttpStatus.BAD_REQUEST) }

        assertThat(start(session).statusCode).isEqualTo(HttpStatus.ACCEPTED)
    }
}
