package com.ledgerlab.control

import com.ledgerlab.control.runs.QueueScheduler
import com.ledgerlab.control.runs.RunQueue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration

class RunQueueTest : IntegrationTest() {

    @Autowired lateinit var queue: RunQueue
    @Autowired lateinit var scheduler: QueueScheduler

    private fun queued(count: Int): List<Any?> {
        val sessionId = sessionOf(invite())
        return (1..count).map { start(sessionId).body!!["runId"] }
    }

    @Test
    fun `a run waiting longer than the queue timeout expires`() {
        val (stale, fresh) = queued(2)
        setRun(stale, createdAgo = props.runs.queueTimeout.plusSeconds(1))
        setRun(fresh, createdAgo = props.runs.queueTimeout.minusSeconds(5))

        val step = queue.advance(ledgerReady = false)

        assertThat(step.expired).isEqualTo(1)
        assertThat(stateOf(stale)).isEqualTo("EXPIRED")
        assertThat(stateOf(fresh)).isEqualTo("QUEUED")
        assertThat(jdbc.sql("SELECT finished_at IS NOT NULL FROM runs WHERE run_id = CAST(:id AS uuid)").param("id", stale.toString()).query(Boolean::class.java).single()).isTrue()
    }

    @Test
    fun `an expired run is not admitted even with free places`() {
        val (stale) = queued(1)
        setRun(stale, createdAgo = props.runs.queueTimeout.plusSeconds(1))

        assertThat(queue.advance(ledgerReady = true).admitted).isZero()
        assertThat(stateOf(stale)).isEqualTo("EXPIRED")
    }

    @Test
    fun `a ready ledger lets the oldest queued runs take the free places`() {
        val runs = queued(props.runs.maxActive + 2)
        setRun(runs[0], state = "RUNNING")
        setRun(runs[1], state = "DRAINING")

        val step = queue.advance(ledgerReady = true)

        assertThat(step.admitted).isEqualTo(props.runs.maxActive - 2)
        assertThat(runs.drop(2).map { stateOf(it) })
            .containsExactly(*(List(props.runs.maxActive - 2) { "CREATED" } + List(2) { "QUEUED" }).toTypedArray())
    }

    @Test
    fun `final runs free their places`() {
        val runs = queued(props.runs.maxActive + 1)
        runs.take(props.runs.maxActive).forEach { setRun(it, state = "CREATED") }
        assertThat(queue.advance(ledgerReady = true).admitted).isZero()

        setRun(runs[0], state = "RECONCILED")

        assertThat(queue.advance(ledgerReady = true).admitted).isEqualTo(1)
        assertThat(stateOf(runs.last())).isEqualTo("CREATED")
    }

    @Test
    fun `no run leaves the queue while the ledger is not ready`() {
        val runs = queued(2)

        assertThat(queue.advance(ledgerReady = false).admitted).isZero()
        assertThat(runs.map { stateOf(it) }).containsOnly("QUEUED")
    }

    @Test
    fun `positions close up when a run leaves the queue`() {
        val sessionId = sessionOf(invite())
        val runs = (1..3).map { start(sessionId).body!!["runId"] }
        setRun(runs[0], state = "CREATED")

        assertThat(getRun(sessionId, runs[0]).body!!["position"]).isNull()
        assertThat(getRun(sessionId, runs[1]).body!!["position"]).isEqualTo(1)
        assertThat(getRun(sessionId, runs[2]).body!!["position"]).isEqualTo(2)
    }

    @Test
    fun `a tick admits runs only when the ledger answers ready`() {
        val (run) = queued(1)

        ledger.status = 503
        assertThat(scheduler.tick().admitted).isZero()
        ledger.status = 200
        ledger.delay = props.ledger.readinessTimeout.plusMillis(500)
        assertThat(scheduler.tick().admitted).isZero()
        assertThat(stateOf(run)).isEqualTo("QUEUED")

        ledger.delay = Duration.ZERO
        assertThat(scheduler.tick().admitted).isEqualTo(1)
        assertThat(stateOf(run)).isEqualTo("CREATED")
    }
}
