package com.ledgerlab.control

import com.ledgerlab.control.RunFixtures.books
import com.ledgerlab.control.RunFixtures.stats
import com.ledgerlab.control.RunFixtures.summary
import com.ledgerlab.control.gateway.GatewayApi
import com.ledgerlab.control.gateway.GatewayClient
import com.ledgerlab.control.http.Reply
import com.ledgerlab.control.http.forRun
import com.ledgerlab.control.ledger.LedgerApi
import com.ledgerlab.control.ledger.LedgerClient
import com.ledgerlab.control.reporting.ReportingApi
import com.ledgerlab.control.reporting.ReportingClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.UUID

@TestPropertySource(
    properties = [
        "control.services.request-timeout=300ms",
        "control.ledger.reconciliation-timeout=3s",
        "control.gateway.summary-timeout=3s",
        "control.reporting.final-stats-timeout=3s",
    ],
)
class WholeRunReadTimeoutTest : IntegrationTest() {

    @Autowired lateinit var ledgerClient: LedgerClient
    @Autowired lateinit var gatewayClient: GatewayClient
    @Autowired lateinit var reportingClient: ReportingClient

    private val slower = Duration.ofMillis(900)

    @Test
    fun `the reads of a whole run wait their own timeout, longer than a plain request's`() {
        val runId = UUID.randomUUID()
        ledger.answer("GET", LedgerApi.RECONCILIATION_PATH.forRun(runId), 200, books(runId), delay = slower)
        gateway.answer("GET", GatewayApi.SUMMARY_PATH.forRun(runId), 200, summary(runId), delay = slower)
        reporting.answer("GET", ReportingApi.STATS_PATH.forRun(runId), 200, stats(runId), delay = slower)

        assertThat(ledgerClient.reconciliation(runId)).isInstanceOf(Reply.Answered::class.java)
        assertThat(gatewayClient.summary(runId)).isInstanceOf(Reply.Answered::class.java)
        assertThat(reportingClient.finalStats(runId)).isInstanceOf(Reply.Answered::class.java)
        assertThat(reportingClient.stats(runId)).isEqualTo(Reply.NoAnswer)
    }
}
