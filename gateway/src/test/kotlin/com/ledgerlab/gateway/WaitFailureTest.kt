package com.ledgerlab.gateway

import com.ledgerlab.gateway.domain.TransferStatus
import com.ledgerlab.common.TransferOutcome
import com.ledgerlab.gateway.service.LedgerResult
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
        val result = LedgerResult(runId, transferId, TransferOutcome.POSTED, null)
        doThrow(DataAccessResourceFailureException("connection lost"))
            .`when`(service).recordResult(any(LedgerResult::class.java) ?: result)
        val failuresBefore = registry.counter("gateway.wait.failed").count()
        Executors.newSingleThreadExecutor().use { pool ->
            val pending = pool.submit<ResponseEntity<Map<*, *>>> { submit(body(transferId, runId), wait = "PT5S") }
            await().atMost(Duration.ofSeconds(10)).until { waiters.size() > 0 }
            waiters.complete(result)

            val answer = pending.get()
            assertThat(answer.statusCode).isEqualTo(HttpStatus.ACCEPTED)
            assertThat(answer.body!!["status"]).isIn(TransferStatus.ACCEPTED.name, TransferStatus.PUBLISHED.name)
            assertThat(answer.headers.getFirst("Retry-After")).isEqualTo("1")
            assertThat(registry.counter("gateway.wait.failed").count()).isEqualTo(failuresBefore + 1)
            assertThat(http.getForEntity("/v1/transfers/$transferId", Map::class.java).statusCode.is2xxSuccessful).isTrue()
        }
    }
}
