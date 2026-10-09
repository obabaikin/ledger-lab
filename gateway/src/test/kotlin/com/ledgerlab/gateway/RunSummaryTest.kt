package com.ledgerlab.gateway

import com.ledgerlab.common.TransferOutcome
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Duration
import java.util.UUID

class RunSummaryTest : IntegrationTest() {

    @Test
    fun `summarizes a run and deletes it`() {
        val runId = UUID.randomUUID()
        val ids = List(3) { UUID.randomUUID() }
        ids.forEach { submit(body(it, runId)) }
        publishResult(runId, ids[0], TransferOutcome.POSTED)
        publishResult(runId, ids[1], TransferOutcome.REJECTED, "BLOCKED_ACCOUNT")

        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            val summary = http.getForEntity("/internal/runs/$runId/summary", Map::class.java).body!!
            assertThat(summary["accepted"]).isEqualTo(3)
            assertThat(summary["published"]).isEqualTo(3)
            assertThat(summary["posted"]).isEqualTo(1)
            assertThat(summary["rejected"]).isEqualTo(1)
            assertThat(summary["pending"]).isEqualTo(1)
            assertThat(summary["rejectionsByReason"]).isEqualTo(mapOf("BLOCKED_ACCOUNT" to 1))
            assertThat(summary["latencyP50Ms"]).isNotNull()
        }

        http.delete("/internal/runs/$runId")
        val after = http.getForEntity("/internal/runs/$runId/summary", Map::class.java).body!!
        assertThat(after["accepted"]).isEqualTo(0)
        assertThat(http.getForEntity("/v1/transfers/${ids[0]}", String::class.java).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
