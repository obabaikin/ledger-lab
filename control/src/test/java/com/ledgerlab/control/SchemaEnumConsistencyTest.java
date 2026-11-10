package com.ledgerlab.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerlab.control.runs.RunResult;
import com.ledgerlab.control.runs.RunState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** RunState and RunResult mirror the CHECK constraints on runs; this fails if one side gains a value the other lacks. */
class SchemaEnumConsistencyTest extends IntegrationTest {

    private int setState(Object runId, String state) {
        return jdbc.sql("UPDATE runs SET state = :state WHERE run_id = CAST(:runId AS uuid)").param("state", state).param("runId", runId.toString()).update();
    }

    private int setResult(Object runId, String result) {
        return jdbc.sql("UPDATE runs SET result = :result WHERE run_id = CAST(:runId AS uuid)").param("result", result).param("runId", runId.toString()).update();
    }

    @Test
    @DisplayName("every RunState is accepted by the runs table, and nothing else")
    void every_RunState_is_accepted_by_the_runs_table_and_nothing_else() {
        var runId = start(sessionOf(invite())).getBody().get("runId");

        for (var state : RunState.values()) {
            assertThat(setState(runId, state.name())).isEqualTo(1);
        }
        assertThatThrownBy(() -> setState(runId, "PAUSED")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("every RunResult is accepted by the runs table, and nothing else")
    void every_RunResult_is_accepted_by_the_runs_table_and_nothing_else() {
        var runId = start(sessionOf(invite())).getBody().get("runId");

        for (var result : RunResult.values()) {
            assertThat(setResult(runId, result.name())).isEqualTo(1);
        }
        assertThatThrownBy(() -> setResult(runId, "GREEN")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
