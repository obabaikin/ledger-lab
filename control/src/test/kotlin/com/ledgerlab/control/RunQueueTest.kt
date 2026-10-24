package com.ledgerlab.control

import com.ledgerlab.control.RunFixtures.GATEWAY_ALL_WARM
import com.ledgerlab.control.RunFixtures.prometheus
import com.ledgerlab.control.runs.QueueScheduler
import com.ledgerlab.control.runs.RunQueue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class RunQueueTest : IntegrationTest() {

    @Autowired lateinit var queue: RunQueue
    @Autowired lateinit var scheduler: QueueScheduler

    private fun queued(count: Int): List<Any?> {
        val token = invite()
        return (1..count).map { start(sessionOf(token)).body!!["runId"] }
    }

    @Test
    fun `a run waiting longer than the queue timeout expires`() {
        val (stale, fresh) = queued(2)
        setRun(stale, createdAgo = props.runs.queueTimeout.plusSeconds(1))
        setRun(fresh, createdAgo = props.runs.queueTimeout.minusSeconds(5))

        val step = queue.advance(bankReady = false)

        assertThat(step.expired).isEqualTo(1)
        assertThat(stateOf(stale)).isEqualTo("EXPIRED")
        assertThat(stateOf(fresh)).isEqualTo("QUEUED")
        assertThat(jdbc.sql("SELECT finished_at IS NOT NULL FROM runs WHERE run_id = CAST(:id AS uuid)").param("id", stale.toString()).query(Boolean::class.java).single()).isTrue()
    }

    @Test
    fun `an expired run is not admitted even with free places`() {
        val (stale) = queued(1)
        setRun(stale, createdAgo = props.runs.queueTimeout.plusSeconds(1))

        assertThat(queue.advance(bankReady = true).admitted).isZero()
        assertThat(stateOf(stale)).isEqualTo("EXPIRED")
    }

    @Test
    fun `a ready ledger lets the oldest queued runs take the free places`() {
        val runs = queued(props.runs.maxActive + 2)
        setRun(runs[0], state = "RUNNING")
        setRun(runs[1], state = "DRAINING")

        val step = queue.advance(bankReady = true)

        assertThat(step.admitted).isEqualTo(props.runs.maxActive - 2)
        assertThat(runs.drop(2).map { stateOf(it) })
            .containsExactly(*(List(props.runs.maxActive - 2) { "CREATED" } + List(2) { "QUEUED" }).toTypedArray())
    }

    private fun queuedAt(vararg tps: Int): List<Any?> {
        val token = invite()
        return tps.map { start(sessionOf(token), tps = it).body!!["runId"] }
    }

    @Test
    fun `runs whose rates fit the total together start together`() {
        val runs = queuedAt(500, 100, 100)

        assertThat(queue.advance(bankReady = true).admitted).isEqualTo(3)
        assertThat(runs.map { stateOf(it) }).containsOnly("CREATED")
    }

    @Test
    fun `a run that would take the total past the limit waits until a running one ends`() {
        val (first, second) = queuedAt(props.runs.maxTotalTps, 100)

        assertThat(queue.advance(bankReady = true).admitted).isEqualTo(1)
        assertThat(stateOf(first)).isEqualTo("CREATED")
        assertThat(stateOf(second)).isEqualTo("QUEUED")

        setRun(first, state = "RECONCILED")

        assertThat(queue.advance(bankReady = true).admitted).isEqualTo(1)
        assertThat(stateOf(second)).isEqualTo("CREATED")
    }

    @Test
    fun `a smaller run behind a waiting one does not overtake it`() {
        val (running, big, small) = queuedAt(500, props.runs.maxTotalTps, 100)
        assertThat(queue.advance(bankReady = true).admitted).isEqualTo(1)

        assertThat(queue.advance(bankReady = true).admitted).isZero()
        assertThat(listOf(running, big, small).map { stateOf(it) }).containsExactly("CREATED", "QUEUED", "QUEUED")
    }

    @Test
    fun `run settings with a rate option above the total are refused`() {
        val aboveTotal = props.runs.maxTotalTps + 1
        assertThatThrownBy { props.runs.copy(tpsOptions = listOf(aboveTotal), defaultTps = aboveTotal) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("would never fit")
    }

    @Test
    fun `runs that fill the total exactly start`() {
        val runs = queuedAt(props.runs.maxTotalTps / 2, props.runs.maxTotalTps / 2)

        assertThat(queue.advance(bankReady = true).admitted).isEqualTo(2)
        assertThat(runs.map { stateOf(it) }).containsOnly("CREATED")
    }

    @Test
    fun `final runs free their places`() {
        val runs = queued(props.runs.maxActive + 1)
        runs.take(props.runs.maxActive).forEach { setRun(it, state = "CREATED") }
        assertThat(queue.advance(bankReady = true).admitted).isZero()

        setRun(runs[0], state = "RECONCILED")

        assertThat(queue.advance(bankReady = true).admitted).isEqualTo(1)
        assertThat(stateOf(runs.last())).isEqualTo("CREATED")
    }

    @Test
    fun `no run leaves the queue while the bank is not ready`() {
        val runs = queued(2)

        assertThat(queue.advance(bankReady = false).admitted).isZero()
        assertThat(runs.map { stateOf(it) }).containsOnly("QUEUED")
    }

    @Test
    fun `positions close up when a run leaves the queue`() {
        val token = invite()
        val runs = (1..3).map { sessionOf(token).let { sessionId -> sessionId to start(sessionId).body!!["runId"] } }
        setRun(runs[0].second, state = "CREATED")

        assertThat(runs.map { (sessionId, runId) -> getRun(sessionId, runId).body!!["position"] }).containsExactly(null, 1, 2)
    }

    @Test
    fun `a tick admits runs only once the ledger holds every partition`() {
        val (run) = queued(1)
        gatewayWarm(1)

        prometheus.status = 503
        assertThat(scheduler.tick().admitted).isZero()
        prometheus.status = 200
        heldPartitions(props.ledger.transferPartitions - 2)
        assertThat(scheduler.tick().admitted).isZero()
        assertThat(stateOf(run)).isEqualTo("QUEUED")

        heldPartitions(props.ledger.transferPartitions)
        assertThat(scheduler.tick().admitted).isEqualTo(1)
        assertThat(stateOf(run)).isEqualTo("CREATED")
    }

    @Test
    fun `a run waits while the gateway warms up, with the ledger ready, and says so in its view`() {
        val token = invite()
        val sessionId = sessionOf(token)
        val run = start(sessionId).body!!["runId"]
        heldPartitions(props.ledger.transferPartitions)
        gatewayWarm(0)

        assertThat(scheduler.tick().admitted).isZero()
        assertThat(getRun(sessionId, run).body!!["waitingFor"]).isEqualTo("WARM_UP")

        gatewayWarm(1)
        assertThat(scheduler.tick().admitted).isEqualTo(1)
        assertThat(getRun(sessionId, run).body!!.let { it["state"] to it["waitingFor"] }).isEqualTo("CREATED" to null)
    }

    @Test
    fun `a run waits while the ledger takes its partitions, with the gateway warm, and says so in its view`() {
        val token = invite()
        val sessionId = sessionOf(token)
        val run = start(sessionId).body!!["runId"]
        heldPartitions(props.ledger.transferPartitions - 1)
        gatewayWarm(1)

        assertThat(scheduler.tick().admitted).isZero()
        assertThat(getRun(sessionId, run).body!!["waitingFor"]).isEqualTo("WARM_UP")

        heldPartitions(props.ledger.transferPartitions)
        assertThat(scheduler.tick().admitted).isEqualTo(1)
        assertThat(getRun(sessionId, run).body!!.let { it["state"] to it["waitingFor"] }).isEqualTo("CREATED" to null)
    }

    @Test
    fun `a queued run waiting only for its place does not say the bank is getting ready`() {
        val token = invite()
        val runs = (1..props.runs.maxActive + 1).map { sessionOf(token).let { sessionId -> sessionId to start(sessionId).body!!["runId"] } }
        runs.take(props.runs.maxActive).forEach { setRun(it.second, state = "CREATED") }
        heldPartitions(props.ledger.transferPartitions)
        gatewayWarm(1)

        assertThat(scheduler.tick().admitted).isZero()

        val (sessionId, waiting) = runs.last()
        assertThat(getRun(sessionId, waiting).body!!.let { it["state"] to it["waitingFor"] }).isEqualTo("QUEUED" to null)
    }

    private fun gatewayWarm(value: Int) = prometheus.answerQuery(
        "GET", "/api/v1/query", "query=$GATEWAY_ALL_WARM", 200, prometheus(listOf(emptyMap<String, String>() to value)),
    )

    private fun heldPartitions(count: Int) = prometheus.answerQuery(
        "GET", "/api/v1/query", "query=sum(ledger_kafka_assigned_partitions)", 200, prometheus(listOf(emptyMap<String, String>() to count)),
    )
}
