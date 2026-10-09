package com.ledgerlab.gateway

import com.ledgerlab.common.TransferOutcome
import com.ledgerlab.common.TransferResultEvent
import com.ledgerlab.gateway.service.ResultWaiters
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

class ResultHandlingTest : IntegrationTest() {

    @Autowired lateinit var waiters: ResultWaiters

    @Test
    fun `a result from the ledger becomes the transfer status`() {
        val transferId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        submit(body(transferId, runId))

        publishResult(runId, transferId, TransferOutcome.REJECTED, "INSUFFICIENT_FUNDS")

        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            val response = http.getForEntity("/v1/transfers/$transferId", Map::class.java)
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body!!["status"]).isEqualTo("REJECTED")
            assertThat(response.body!!["rejectReason"]).isEqualTo("INSUFFICIENT_FUNDS")
        }
    }

    @Test
    fun `a waiting request is answered as soon as the result arrives`() {
        val transferId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val pool = Executors.newSingleThreadExecutor()

        val pending = pool.submit<org.springframework.http.ResponseEntity<Map<*, *>>> { submit(body(transferId, runId), wait = "PT5S") }
        await().atMost(Duration.ofSeconds(10)).until { waiters.size() > 0 }
        publishResult(runId, transferId, TransferOutcome.POSTED)

        val response = pending.get()
        pool.shutdown()
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["status"]).isEqualTo("POSTED")
        assertThat(waiters.size()).isZero()
    }

    @Test
    fun `a final answer to a waiting client is already in the database`() {
        val transferId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val pool = Executors.newSingleThreadExecutor()

        val pending = pool.submit<org.springframework.http.ResponseEntity<Map<*, *>>> { submit(body(transferId, runId), wait = "PT5S") }
        await().atMost(Duration.ofSeconds(10)).until { waiters.size() > 0 }
        // Wake the request directly, bypassing Kafka, so the recorder cannot write the status first.
        waiters.complete(TransferResultEvent(runId, transferId, TransferOutcome.POSTED, null, Instant.now()))

        val answer = pending.get()
        pool.shutdown()
        assertThat(answer.body!!["status"]).isEqualTo("POSTED")
        val stored = http.getForEntity("/v1/transfers/$transferId", Map::class.java)
        assertThat(stored.body!!["status"]).isEqualTo("POSTED")
        assertThat(stored.body!!["resultedAt"]).isEqualTo(answer.body!!["resultedAt"])
    }

    @Test
    fun `two requests waiting for the same transfer are both answered`() {
        val transferId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(2)

        val first = pool.submit<org.springframework.http.ResponseEntity<Map<*, *>>> { submit(body(transferId, runId), wait = "PT0.5S") }
        val second = pool.submit<org.springframework.http.ResponseEntity<Map<*, *>>> { submit(body(transferId, runId), wait = "PT5S") }
        await().atMost(Duration.ofSeconds(10)).until { waiters.size() == 2 }
        assertThat(first.get().statusCode).isEqualTo(HttpStatus.ACCEPTED) // the short wait times out first
        await().atMost(Duration.ofSeconds(5)).until { waiters.size() == 1 }
        publishResult(runId, transferId, TransferOutcome.POSTED)

        val response = second.get()
        pool.shutdown()
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["status"]).isEqualTo("POSTED")
        assertThat(waiters.size()).isZero()
    }

    @Test
    fun `a waiting request times out with 202 and the current state`() {
        val response = submit(body(), wait = "PT0.3S")

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(response.body!!["status"]).isIn("ACCEPTED", "PUBLISHED")
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
            http.getForEntity("/v1/transfers/$transferId", Map::class.java).body!!["status"] == "POSTED"
        }

        val again = submit(request, wait = "PT5S")

        assertThat(again.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(again.body!!["status"]).isEqualTo("POSTED")
    }

    @Test
    fun `a result that arrives before the outbox mark does not get downgraded`() {
        val transferId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        submit(body(transferId, runId))
        publishResult(runId, transferId, TransferOutcome.POSTED)

        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            val response = http.getForEntity("/v1/transfers/$transferId", Map::class.java).body!!
            assertThat(response["status"]).isEqualTo("POSTED")
        }
        Thread.sleep(300) // give the outbox publisher time to run after the result
        assertThat(http.getForEntity("/v1/transfers/$transferId", Map::class.java).body!!["status"]).isEqualTo("POSTED")
    }
}
