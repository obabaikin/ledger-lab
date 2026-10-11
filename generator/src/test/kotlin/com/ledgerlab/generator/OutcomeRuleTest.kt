package com.ledgerlab.generator

import com.ledgerlab.generator.bank.RejectReason
import com.ledgerlab.generator.bank.TransferStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class OutcomeRuleTest {

    private fun result(outcome: SubmissionOutcome, status: TransferStatus? = null, rejectReason: RejectReason? = null) =
        SubmissionResult(status, rejectReason, attempts = 1, outcome = outcome, latency = Duration.ZERO)

    private val accepted = result(SubmissionOutcome.ACKNOWLEDGED, TransferStatus.ACCEPTED)
    private val posted = result(SubmissionOutcome.ACKNOWLEDGED, TransferStatus.POSTED)
    private val rejected = result(SubmissionOutcome.ACKNOWLEDGED, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT)
    private val unknown = result(SubmissionOutcome.UNKNOWN)
    private val failed = result(SubmissionOutcome.FAILED)

    @Test
    fun `any 2xx acknowledges, whatever the other sends ended with`() {
        assertThat(combine(listOf(unknown, accepted))).isSameAs(accepted)
        assertThat(combine(listOf(accepted, failed))).isSameAs(accepted)
        assertThat(combine(listOf(failed, unknown, posted))).isSameAs(posted)
    }

    @Test
    fun `without a 2xx a send that may have arrived keeps the transfer unknown`() {
        assertThat(combine(listOf(failed, unknown)).outcome).isEqualTo(SubmissionOutcome.UNKNOWN)
        assertThat(combine(listOf(unknown, failed)).outcome).isEqualTo(SubmissionOutcome.UNKNOWN)
        assertThat(combine(listOf(failed, failed)).outcome).isEqualTo(SubmissionOutcome.FAILED)
        assertThat(combine(listOf(failed)).outcome).isEqualTo(SubmissionOutcome.FAILED)
    }

    @Test
    fun `a final status outranks an accepted one, and a later answer an earlier one`() {
        assertThat(combine(listOf(rejected, accepted))).isSameAs(rejected)
        assertThat(combine(listOf(accepted, posted))).isSameAs(posted)
        val laterAccepted = result(SubmissionOutcome.ACKNOWLEDGED, TransferStatus.ACCEPTED)
        assertThat(combine(listOf(accepted, laterAccepted))).isSameAs(laterAccepted)
    }
}
