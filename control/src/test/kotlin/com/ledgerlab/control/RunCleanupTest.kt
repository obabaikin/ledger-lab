package com.ledgerlab.control

import com.ledgerlab.control.gateway.GatewayApi
import com.ledgerlab.control.http.forRun
import com.ledgerlab.control.ledger.LedgerApi
import com.ledgerlab.control.reporting.ReportingApi
import com.ledgerlab.control.runs.RunCleanup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class RunCleanupTest : IntegrationTest() {

    @Autowired lateinit var cleanup: RunCleanup

    private fun ended(state: String, hoursAgo: Long): UUID {
        val runId = UUID.fromString(start(sessionOf(invite())).body!!["runId"] as String)
        jdbc.sql("UPDATE runs SET state = :state, finished_at = clock_timestamp() - CAST(:hours AS bigint) * interval '1 hour' WHERE run_id = :id")
            .param("state", state).param("hours", hoursAgo).param("id", runId).update()
        return runId
    }

    private fun purged(runId: UUID): Boolean =
        jdbc.sql("SELECT purged_at IS NOT NULL FROM runs WHERE run_id = :id").param("id", runId).query(Boolean::class.java).single()

    @Test
    fun `runs that ended a retention ago lose their bank data and keep their row`() {
        val reconciled = ended("RECONCILED", 25)
        val failed = ended("FAILED", 25)
        val recent = ended("RECONCILED", 23)
        val expired = ended("EXPIRED", 25)
        ledger.status = 204
        gateway.status = 204
        reporting.status = 204

        assertThat(cleanup.purge()).isEqualTo(2)

        assertThat(listOf(reconciled, failed).map { purged(it) }).containsOnly(true)
        assertThat(listOf(recent, expired).map { purged(it) }).containsOnly(false)
        assertThat(ledger.callsTo("DELETE", LedgerApi.RUN_PATH.forRun(reconciled))).hasSize(1)
        assertThat(gateway.callsTo("DELETE", GatewayApi.RUN_PATH.forRun(reconciled))).hasSize(1)
        assertThat(reporting.callsTo("DELETE", ReportingApi.RUN_PATH.forRun(reconciled))).hasSize(1)
        assertThat(ledger.callsTo("DELETE", LedgerApi.RUN_PATH.forRun(expired))).isEmpty()
        assertThat(stateOf(reconciled)).isEqualTo("RECONCILED")
    }

    @Test
    fun `a run the bank no longer knows counts as purged`() {
        val runId = ended("FAILED", 25)
        ledger.status = 404
        gateway.status = 404
        reporting.status = 404

        cleanup.purge()

        assertThat(purged(runId)).isTrue()
    }

    @Test
    fun `a delete that fails is repeated on the next round, and a purged run is not deleted again`() {
        val runId = ended("RECONCILED", 25)
        ledger.status = 204
        gateway.status = 503
        reporting.status = 204

        cleanup.purge()
        assertThat(purged(runId)).isFalse()

        gateway.status = 204
        cleanup.purge()
        cleanup.purge()
        assertThat(purged(runId)).isTrue()
        assertThat(gateway.callsTo("DELETE", GatewayApi.RUN_PATH.forRun(runId))).hasSize(2)
    }

    @Test
    fun `a run whose numbers the reporting service still holds is not purged yet`() {
        val runId = ended("RECONCILED", 25)
        ledger.status = 204
        gateway.status = 204
        reporting.status = 503

        cleanup.purge()

        assertThat(purged(runId)).isFalse()
        assertThat(reporting.callsTo("DELETE", ReportingApi.RUN_PATH.forRun(runId))).hasSize(1)
    }
}
