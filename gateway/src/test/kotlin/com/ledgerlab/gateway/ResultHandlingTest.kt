package com.ledgerlab.gateway

import com.ledgerlab.common.RejectReason
import com.ledgerlab.gateway.domain.TransferStatus
import com.ledgerlab.common.TransferOutcome
import com.ledgerlab.gateway.service.LedgerResult
import com.ledgerlab.gateway.service.ResultWaiters
import com.ledgerlab.gateway.service.TransferService
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors

class ResultHandlingTest : IntegrationTest() {

    @Autowired lateinit var waiters: ResultWaiters
    @Autowired lateinit var registry: MeterRegistry
    @Autowired lateinit var service: TransferService

    @Test
    fun `a result from the ledger becomes the transfer status`() {
        val transferId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        submit(body(transferId, runId))

        publishResult(runId, transferId, TransferOutcome.REJECTED, RejectReason.INSUFFICIENT_FUNDS)

        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            val response = http.getForEntity("/v1/transfers/$transferId", Map::class.java)
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body!!["status"]).isEqualTo(TransferStatus.REJECTED.name)
            assertThat(response.body!!["rejectReason"]).isEqualTo(RejectReason.INSUFFICIENT_FUNDS.name)
        }
    }

    @Test
    fun `a waiting request is answered with the status the recorder committed`() {
        val readsBefore = registry.summary("gateway.wait.reads").count()
        val transferId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        Executors.newSingleThreadExecutor().use { pool ->
            val pending = pool.submit<org.springframework.http.ResponseEntity<Map<*, *>>> { submit(body(transferId, runId), wait = "PT5S") }
            await().atMost(Duration.ofSeconds(10)).until { waiters.size() > 0 }
            publishResult(runId, transferId, TransferOutcome.POSTED)

            val response = pending.get()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body!!["status"]).isEqualTo(TransferStatus.POSTED.name)
            assertThat(waiters.size()).isZero()
            assertThat(registry.summary("gateway.wait.reads").count()).isGreaterThan(readsBefore)
        }
    }

    @Test
    fun `a woken request only reads, so without the recorder's write it answers 202 after its last read and the row stays as it was`() {
        val readsBefore = registry.summary("gateway.wait.reads").totalAmount()
        val transferId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        Executors.newSingleThreadExecutor().use { pool ->
            val pending = pool.submit<org.springframework.http.ResponseEntity<Map<*, *>>> { submit(body(transferId, runId), wait = "PT5S") }
            await().atMost(Duration.ofSeconds(10)).until { waiters.size() > 0 }
            // Wake the request directly, bypassing Kafka, so the recorder never writes this result.
            waiters.complete(LedgerResult(runId, transferId, TransferOutcome.POSTED, null))

            val answer = pending.get()
            assertThat(answer.statusCode).isEqualTo(HttpStatus.ACCEPTED)
            val stored = http.getForEntity("/v1/transfers/$transferId", Map::class.java)
            assertThat(stored.body!!["status"]).isIn(TransferStatus.ACCEPTED.name, TransferStatus.PUBLISHED.name)
            assertThat(stored.body!!["resultedAt"]).isNull()
            assertThat(registry.summary("gateway.wait.reads").totalAmount() - readsBefore).isEqualTo(props.waitRead.maxReads.toDouble())
        }
    }

    @Test
    fun `two requests waiting for the same transfer are both answered`() {
        val transferId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        Executors.newFixedThreadPool(2).use { pool ->
            val first = pool.submit<org.springframework.http.ResponseEntity<Map<*, *>>> { submit(body(transferId, runId), wait = "PT0.5S") }
            val second = pool.submit<org.springframework.http.ResponseEntity<Map<*, *>>> { submit(body(transferId, runId), wait = "PT5S") }
            await().atMost(Duration.ofSeconds(10)).until { waiters.size() == 2 }
            assertThat(first.get().statusCode).isEqualTo(HttpStatus.ACCEPTED) // the short wait times out first
            await().atMost(Duration.ofSeconds(5)).until { waiters.size() == 1 }
            publishResult(runId, transferId, TransferOutcome.POSTED)

            val response = second.get()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body!!["status"]).isEqualTo(TransferStatus.POSTED.name)
            assertThat(waiters.size()).isZero()
        }
    }

    @Test
    fun `a waiting request times out with 202 and the current state`() {
        val response = submit(body(), wait = "PT0.3S")

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(response.body!!["status"]).isIn(TransferStatus.ACCEPTED.name, TransferStatus.PUBLISHED.name)
        assertThat(response.headers.getFirst("Retry-After")).isEqualTo("1")
        assertThat(waiters.size()).isZero()
    }

    @Test
    fun `a repeated submission after the result returns the final state immediately`() {
        val transferId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val request = body(transferId, runId)
        submit(request)
        publishResult(runId, transferId, TransferOutcome.POSTED)
        await().atMost(Duration.ofSeconds(30)).until {
            http.getForEntity("/v1/transfers/$transferId", Map::class.java).body!!["status"] == TransferStatus.POSTED.name
        }

        val again = submit(request, wait = "PT5S")

        assertThat(again.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(again.body!!["status"]).isEqualTo(TransferStatus.POSTED.name)
    }

    @Test
    fun `a result that arrives before the outbox mark does not get downgraded`() {
        val transferId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        submit(body(transferId, runId))
        publishResult(runId, transferId, TransferOutcome.POSTED)

        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            val response = http.getForEntity("/v1/transfers/$transferId", Map::class.java).body!!
            assertThat(response["status"]).isEqualTo(TransferStatus.POSTED.name)
        }
        Thread.sleep(300) // give the outbox publisher time to run after the result
        assertThat(http.getForEntity("/v1/transfers/$transferId", Map::class.java).body!!["status"]).isEqualTo(TransferStatus.POSTED.name)
    }

    @Test
    fun `a poll of results is recorded in one update and a final status is never overwritten`() {
        val runId = UUID.randomUUID()
        val (posted, rejected, unknown) = List(3) { UUID.randomUUID() }
        submit(body(posted, runId))
        submit(body(rejected, runId))

        service.recordResults(
            listOf(
                LedgerResult(runId, posted, TransferOutcome.POSTED, null),
                LedgerResult(runId, rejected, TransferOutcome.REJECTED, RejectReason.BLOCKED_ACCOUNT.name),
                LedgerResult(runId, unknown, TransferOutcome.POSTED, null),
            ),
        )
        service.recordResults(listOf(LedgerResult(runId, posted, TransferOutcome.REJECTED, RejectReason.INSUFFICIENT_FUNDS.name)))

        assertThat(service.find(posted)!!.status).isEqualTo(TransferStatus.POSTED)
        assertThat(service.find(posted)!!.rejectReason).isNull()
        assertThat(service.find(rejected)!!.status).isEqualTo(TransferStatus.REJECTED)
        assertThat(service.find(rejected)!!.rejectReason).isEqualTo(RejectReason.BLOCKED_ACCOUNT)
        assertThat(service.find(unknown)).isNull()
    }
}
