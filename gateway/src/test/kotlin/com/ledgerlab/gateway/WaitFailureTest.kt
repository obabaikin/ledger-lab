package com.ledgerlab.gateway

import com.ledgerlab.common.TransferOutcome
import com.ledgerlab.common.TransferResultEvent
import com.ledgerlab.gateway.service.ResultWaiters
import com.ledgerlab.gateway.service.TransferService
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

class WaitFailureTest : IntegrationTest() {

    @MockitoSpyBean lateinit var service: TransferService
    @Autowired lateinit var waiters: ResultWaiters
    @Autowired lateinit var registry: MeterRegistry

    @Test
    fun `a database failure while waiting answers 202, not 5xx, because the transfer is committed`() {
        val transferId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val event = TransferResultEvent(runId, transferId, TransferOutcome.POSTED, null, Instant.now())
        doThrow(DataAccessResourceFailureException("connection lost"))
            .`when`(service).recordResult(any(TransferResultEvent::class.java) ?: event)
        val failuresBefore = registry.counter("gateway.wait.failed").count()
        val pool = Executors.newSingleThreadExecutor()

        val pending = pool.submit<ResponseEntity<Map<*, *>>> { submit(body(transferId, runId), wait = "PT5S") }
        await().atMost(Duration.ofSeconds(10)).until { waiters.size() > 0 }
        waiters.complete(event)

        val answer = pending.get()
        pool.shutdown()
        assertThat(answer.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(answer.body!!["status"]).isIn("ACCEPTED", "PUBLISHED")
        assertThat(answer.headers.getFirst("Retry-After")).isEqualTo("1")
        assertThat(registry.counter("gateway.wait.failed").count()).isEqualTo(failuresBefore + 1)
        assertThat(http.getForEntity("/v1/transfers/$transferId", Map::class.java).statusCode.is2xxSuccessful).isTrue()
    }
}
