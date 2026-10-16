package com.ledgerlab.control

import com.ledgerlab.control.gateway.RunSummary
import com.ledgerlab.control.generator.ClientReconciliationReport
import com.ledgerlab.control.ledger.ReconciliationReport
import com.ledgerlab.control.reporting.RunStats
import com.ledgerlab.control.runs.ClientSide
import com.ledgerlab.control.runs.RunResult
import com.ledgerlab.control.runs.projectionMatchesBooks
import com.ledgerlab.control.runs.verdictOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID

class VerdictTest {

    private val runId = UUID.randomUUID()
    private val mapper = jacksonObjectMapper()

    private inline fun <reified T> read(value: Any): T = mapper.readValue(mapper.writeValueAsString(value))

    private fun summary(posted: Long = 980, rejected: Long = 15, reasons: Map<String, Long> = mapOf("BLOCKED_ACCOUNT" to 15L)): RunSummary =
        read(RunFixtures.summary(runId, posted = posted, rejected = rejected, reasons = reasons))

    private fun books(posted: Long = 980, rejected: Long = 15, balanced: Boolean = true, reasons: Map<String, Long> = mapOf("BLOCKED_ACCOUNT" to 15L)): ReconciliationReport =
        read(RunFixtures.books(runId, posted = posted, rejected = rejected, balanced = balanced, reasons = reasons))

    private fun stats(posted: Long = 980, rejected: Long = 15): RunStats = read(RunFixtures.stats(runId, posted = posted, rejected = rejected))

    private fun client(balanced: Boolean = true, statementFinal: Boolean = true): ClientSide =
        ClientSide.Reconciled(read<ClientReconciliationReport>(RunFixtures.clientReport(runId, balanced, statementFinal)))

    @Test
    fun `all three sides agreeing on a finished drain is balanced`() {
        assertThat(verdictOf(summary(), books(), client()).result).isEqualTo(RunResult.BALANCED)
    }

    @Test
    fun `unbalanced books are a break`() {
        assertThat(verdictOf(summary(), books(balanced = false), client()).result).isEqualTo(RunResult.BREAKS)
    }

    @Test
    fun `gateway and ledger telling different outcomes is a break`() {
        val report = verdictOf(summary(posted = 981, rejected = 14), books(), client())

        assertThat(report.result).isEqualTo(RunResult.BREAKS)
        assertThat(report.bankViewsAgree).isFalse()
    }

    @Test
    fun `gateway and ledger giving different reasons is a break`() {
        val report = verdictOf(summary(reasons = mapOf("INSUFFICIENT_FUNDS" to 15L)), books(), client())

        assertThat(report.result).isEqualTo(RunResult.BREAKS)
    }

    @Test
    fun `a client break is a break`() {
        assertThat(verdictOf(summary(), books(), client(balanced = false)).result).isEqualTo(RunResult.BREAKS)
    }

    @Test
    fun `a statement not final makes the verdict incomplete`() {
        val report = verdictOf(summary(), books(), client(statementFinal = false))

        assertThat(report.result).isEqualTo(RunResult.INCOMPLETE)
        assertThat(report.reasons).containsExactly("statement not final")
    }

    @Test
    fun `a gateway that knows more results than it accepted is a break, not lag`() {
        assertThat(verdictOf(summary(posted = 981), books(), client()).result).isEqualTo(RunResult.BREAKS)
    }

    @Test
    fun `a break outweighs an incomplete side`() {
        val report = verdictOf(summary(), books(balanced = false), ClientSide.Missing("client lost"))

        assertThat(report.result).isEqualTo(RunResult.BREAKS)
        assertThat(report.reasons).containsExactly("client lost")
    }

    @Test
    fun `the report keeps the bank's latency at the end of the run`() {
        val report = verdictOf(summary(), books(), client())

        assertThat(report.bankLatencyP50Ms).isEqualTo(12.0)
        assertThat(report.bankLatencyP99Ms).isEqualTo(480.5)
    }

    @Test
    fun `rejections by reason are the ledger's`() {
        val report = verdictOf(summary(reasons = mapOf("INSUFFICIENT_FUNDS" to 15L)), books(), client())

        assertThat(report.rejectionsByReason).isEqualTo(mapOf("BLOCKED_ACCOUNT" to 15L))
    }

    @Test
    fun `a projection that differs from the books only in posted, or only in rejected, does not match them`() {
        assertThat(projectionMatchesBooks(stats(), summary(), books())).isTrue()
        assertThat(projectionMatchesBooks(stats(posted = 979), summary(), books())).isFalse()
        assertThat(projectionMatchesBooks(stats(rejected = 14), summary(), books())).isFalse()
    }
}
