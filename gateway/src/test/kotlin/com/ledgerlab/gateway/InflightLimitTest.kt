package com.ledgerlab.gateway

import com.ledgerlab.gateway.api.GatewayErrors
import com.ledgerlab.gateway.service.ResultWaiters
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors

@TestPropertySource(properties = ["gateway.inflight.max-concurrent=1"])
class InflightLimitTest : IntegrationTest() {

    @Autowired lateinit var waiters: ResultWaiters
    @Autowired lateinit var registry: MeterRegistry

    @Test
    fun `requests over the in-flight limit get 503 busy with Retry-After, waiting ones included`() {
        val refusedBefore = registry.counter("gateway.transfers", "outcome", "refused").count()
        val runId = UUID.randomUUID()
        val refusedId = UUID.randomUUID()
        Executors.newSingleThreadExecutor().use { pool ->
            val waiting = pool.submit<ResponseEntity<Map<*, *>>> { submit(body(runId = runId), wait = "PT3S") }
            // Written and now waiting for a result that never comes.
            await().atMost(Duration.ofSeconds(10)).until { waiters.size() > 0 }

            val refused = submit(body(refusedId, runId))
            assertThat(refused.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(refused.body!!["error"]).isEqualTo(GatewayErrors.BUSY)
            assertThat(refused.headers.getFirst("Retry-After")).isEqualTo("1")
            assertThat(registry.counter("gateway.transfers", "outcome", "refused").count()).isEqualTo(refusedBefore + 1)
            assertThat(http.getForEntity("/v1/transfers/$refusedId", Map::class.java).statusCode).isEqualTo(HttpStatus.NOT_FOUND)

            assertThat(waiting.get().statusCode).isEqualTo(HttpStatus.ACCEPTED)
        }
        assertThat(submit(body(refusedId, runId)).statusCode).isEqualTo(HttpStatus.ACCEPTED)
    }
}
