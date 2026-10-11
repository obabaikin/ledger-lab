package com.ledgerlab.gateway

import com.ledgerlab.gateway.domain.TransferStatus
import com.ledgerlab.gateway.kafka.ResultEventReader
import com.ledgerlab.gateway.service.ResultWaiters
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/** A ledger one version ahead may reject for a reason this gateway does not know yet (ADR 0011). */
class UnknownRejectReasonTest : IntegrationTest() {

    @Autowired lateinit var waiters: ResultWaiters
    @Autowired lateinit var registry: MeterRegistry

    private fun unknownReasons(listener: ResultEventReader.Listener) =
        registry.counter(ResultEventReader.UNKNOWN_REASON_METRIC, ResultEventReader.LISTENER_TAG, listener.tag).count()

    private fun rejectedFor(runId: UUID, transferId: UUID, reason: String) =
        """{"runId":"$runId","transferId":"$transferId","outcome":"REJECTED","rejectReason":"$reason","processedAt":"${Instant.now()}"}"""

    @Test
    fun `an unknown reason still rejects the transfer, is kept as received and counted on both paths`() {
        val transferId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val recordedBefore = unknownReasons(ResultEventReader.Listener.RECORDER)
        val notifiedBefore = unknownReasons(ResultEventReader.Listener.NOTIFIER)
        Executors.newSingleThreadExecutor().use { pool ->
            val waiting = pool.submit<ResponseEntity<Map<*, *>>> { submit(body(transferId, runId), wait = "PT5S") }
            await().atMost(Duration.ofSeconds(10)).until { waiters.size() > 0 }

            publishRawResult(runId, transferId, rejectedFor(runId, transferId, UNKNOWN_REASON))

            val answer = waiting.get()
            assertThat(answer.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(answer.body!!["status"]).isEqualTo(TransferStatus.REJECTED.name)
            assertThat(answer.body!!["rejectReason"]).isNull()
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            val stored = http.getForEntity("/v1/transfers/$transferId", Map::class.java).body!!
            assertThat(stored["status"]).isEqualTo(TransferStatus.REJECTED.name)
            assertThat(stored["rejectReason"]).isNull()
            val summary = http.getForEntity("/internal/runs/$runId/summary", Map::class.java).body!!
            assertThat(summary["rejectionsByReason"]).isEqualTo(mapOf(UNKNOWN_REASON to 1))
            assertThat(unknownReasons(ResultEventReader.Listener.RECORDER)).isEqualTo(recordedBefore + 1)
            assertThat(unknownReasons(ResultEventReader.Listener.NOTIFIER)).isEqualTo(notifiedBefore + 1)
        }
    }

    private companion object {
        const val UNKNOWN_REASON = "CARD_EXPIRED"
    }
}
