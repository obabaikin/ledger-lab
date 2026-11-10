package com.ledgerlab.generator;

import static com.ledgerlab.generator.OutcomeRule.combine;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.TransferStatus;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutcomeRuleTest {

    private static SubmissionResult result(SubmissionOutcome outcome) {
        return result(outcome, null, null);
    }

    private static SubmissionResult result(SubmissionOutcome outcome, TransferStatus status, RejectReason rejectReason) {
        return new SubmissionResult(status, rejectReason, 1, outcome, Duration.ZERO, 0);
    }

    private final SubmissionResult accepted = result(SubmissionOutcome.ACKNOWLEDGED, TransferStatus.ACCEPTED, null);
    private final SubmissionResult posted = result(SubmissionOutcome.ACKNOWLEDGED, TransferStatus.POSTED, null);
    private final SubmissionResult rejected = result(SubmissionOutcome.ACKNOWLEDGED, TransferStatus.REJECTED, RejectReason.BLOCKED_ACCOUNT);
    private final SubmissionResult unknown = result(SubmissionOutcome.UNKNOWN);
    private final SubmissionResult failed = result(SubmissionOutcome.FAILED);

    @Test
    @DisplayName("any 2xx acknowledges, whatever the other sends ended with")
    void any_2xx_acknowledges_whatever_the_other_sends_ended_with() {
        assertThat(combine(List.of(unknown, accepted))).isSameAs(accepted);
        assertThat(combine(List.of(accepted, failed))).isSameAs(accepted);
        assertThat(combine(List.of(failed, unknown, posted))).isSameAs(posted);
    }

    @Test
    void without_a_2xx_a_send_that_may_have_arrived_keeps_the_transfer_unknown() {
        assertThat(combine(List.of(failed, unknown)).outcome()).isEqualTo(SubmissionOutcome.UNKNOWN);
        assertThat(combine(List.of(unknown, failed)).outcome()).isEqualTo(SubmissionOutcome.UNKNOWN);
        assertThat(combine(List.of(failed, failed)).outcome()).isEqualTo(SubmissionOutcome.FAILED);
        assertThat(combine(List.of(failed)).outcome()).isEqualTo(SubmissionOutcome.FAILED);
    }

    @Test
    @DisplayName("a final status outranks an accepted one, and a later answer an earlier one")
    void a_final_status_outranks_an_accepted_one_and_a_later_answer_an_earlier_one() {
        assertThat(combine(List.of(rejected, accepted))).isSameAs(rejected);
        assertThat(combine(List.of(accepted, posted))).isSameAs(posted);
        var laterAccepted = result(SubmissionOutcome.ACKNOWLEDGED, TransferStatus.ACCEPTED, null);
        assertThat(combine(List.of(accepted, laterAccepted))).isSameAs(laterAccepted);
    }
}
