package com.ledgerlab.control

import com.ledgerlab.control.runs.RunState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException

/** RunState mirrors the CHECK constraint on runs.state; this fails if one side gains a value the other lacks. */
class SchemaEnumConsistencyTest : IntegrationTest() {

    private fun setState(runId: Any?, state: String) =
        jdbc.sql("UPDATE runs SET state = :state WHERE run_id = CAST(:runId AS uuid)").param("state", state).param("runId", runId.toString()).update()

    @Test
    fun `every RunState is accepted by the runs table, and nothing else`() {
        val runId = start(sessionOf(invite())).body!!["runId"]

        RunState.entries.forEach { assertThat(setState(runId, it.name)).isEqualTo(1) }
        assertThatThrownBy { setState(runId, "PAUSED") }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
