package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerlab.control.periods.PeriodFailure;
import com.ledgerlab.control.periods.PeriodState;
import com.ledgerlab.control.periods.Verdict;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** The periods' enums mirror the CHECK constraints on periods; this fails if one side gains a value the other lacks. */
class SchemaEnumConsistencyTest extends IntegrationTest {

    private static final Instant PERIOD = Instant.parse("2026-11-10T11:00:00Z");

    private int accepted(String state, String verdict, String failure) {
        jdbc.sql("DELETE FROM periods").update();
        return jdbc.sql(
            """
            INSERT INTO periods (period_start, period_end, state, verdict, failure)
            VALUES (:start, CAST(:start AS timestamptz) + interval '1 hour', :state, CAST(:verdict AS text), CAST(:failure AS text))
            """)
            .param("start", Timestamp.from(PERIOD))
            .param("state", state)
            .param("verdict", verdict)
            .param("failure", failure)
            .update();
    }

    @Test
    @DisplayName("every PeriodState is accepted by the periods table, and nothing else")
    void every_PeriodState_is_accepted_by_the_periods_table_and_nothing_else() {
        for (var state : PeriodState.values()) {
            var verdict = state == PeriodState.RECONCILED ? Verdict.BALANCED.name() : null;
            var failure = state == PeriodState.FAILED ? PeriodFailure.BANK_UNAVAILABLE.name() : null;
            assertThat(accepted(state.name(), verdict, failure)).as(state.name()).isEqualTo(1);
        }
        assertThatThrownBy(() -> accepted("PAUSED", null, null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("every Verdict and every PeriodFailure is accepted by the periods table, and nothing else")
    void every_Verdict_and_every_PeriodFailure_is_accepted_by_the_periods_table_and_nothing_else() {
        for (var verdict : Verdict.values()) {
            assertThat(accepted(PeriodState.RECONCILED.name(), verdict.name(), null)).as(verdict.name()).isEqualTo(1);
        }
        for (var failure : PeriodFailure.values()) {
            assertThat(accepted(PeriodState.FAILED.name(), null, failure.name())).as(failure.name()).isEqualTo(1);
        }
        assertThatThrownBy(() -> accepted(PeriodState.RECONCILED.name(), "GREEN", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> accepted(PeriodState.FAILED.name(), null, "TIRED")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a verdict belongs to a reconciled period only, a failure to a failed one only")
    void a_verdict_belongs_to_a_reconciled_period_only_a_failure_to_a_failed_one_only() {
        assertThatThrownBy(() -> accepted(PeriodState.DRAINING.name(), Verdict.BALANCED.name(), null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> accepted(PeriodState.RECONCILED.name(), null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> accepted(PeriodState.FAILED.name(), null, null)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
