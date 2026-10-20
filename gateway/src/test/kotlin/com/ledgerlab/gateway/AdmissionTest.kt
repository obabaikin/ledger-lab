package com.ledgerlab.gateway

import com.ledgerlab.gateway.api.GatewayErrors
import com.ledgerlab.gateway.service.TransferRequest
import com.ledgerlab.gateway.service.TransferService
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@TestPropertySource(properties = ["gateway.admission.max-concurrent=1", "gateway.admission.wait=50ms"])
class AdmissionTest : IntegrationTest() {

    @MockitoSpyBean lateinit var service: TransferService
    @Autowired lateinit var registry: MeterRegistry

    @Test
    fun `a submission over the limit is refused with 503 busy before it is written, and a retry is taken`() {
        val held = CountDownLatch(1)
        val inside = AtomicInteger()
        val sample = TransferRequest(UUID.randomUUID(), UUID.randomUUID(), 1, 2, 100)
        doAnswer { call ->
            inside.incrementAndGet()
            held.await()
            call.callRealMethod()
        }.`when`(service).accept(any(TransferRequest::class.java) ?: sample)
        val refusedBefore = registry.counter("gateway.transfers", "outcome", "refused").count()
        val runId = UUID.randomUUID()
        val refusedId = UUID.randomUUID()
        Executors.newSingleThreadExecutor().use { pool ->
            val first = pool.submit<ResponseEntity<Map<*, *>>> { submit(body(runId = runId)) }
            await().atMost(Duration.ofSeconds(10)).until { inside.get() == 1 }

            val refused = submit(body(refusedId, runId))
            assertThat(refused.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(refused.body!!["error"]).isEqualTo(GatewayErrors.BUSY)
            assertThat(refused.headers.getFirst("Retry-After")).isEqualTo("1")
            assertThat(registry.counter("gateway.transfers", "outcome", "refused").count()).isEqualTo(refusedBefore + 1)
            assertThat(http.getForEntity("/v1/transfers/$refusedId", Map::class.java).statusCode).isEqualTo(HttpStatus.NOT_FOUND)

            held.countDown()
            assertThat(first.get().statusCode.is2xxSuccessful).isTrue()
        }
        assertThat(submit(body(refusedId, runId)).statusCode.is2xxSuccessful).isTrue()
    }
}
