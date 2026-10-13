package com.ledgerlab.control

import com.ledgerlab.control.RunFixtures.duplicates
import com.ledgerlab.control.RunFixtures.prometheus
import com.ledgerlab.control.RunFixtures.simulation
import com.ledgerlab.control.RunFixtures.stats
import com.ledgerlab.control.generator.GeneratorApi
import com.ledgerlab.control.http.forRun
import com.ledgerlab.control.reporting.ReportingApi
import com.ledgerlab.control.runs.Conductor
import com.ledgerlab.control.runs.RunQueue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class LiveRunsTest : IntegrationTest() {

    @Autowired lateinit var conductor: Conductor
    @Autowired lateinit var queue: RunQueue

    private fun running(): Pair<UUID, UUID> {
        val sessionId = sessionOf(invite())
        val runId = UUID.fromString(start(sessionId).body!!["runId"] as String)
        queue.advance(ledgerReady = true)
        jdbc.sql("UPDATE runs SET state = 'RUNNING', started_at = clock_timestamp() WHERE run_id = :id").param("id", runId).update()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 200, simulation(runId, "RUNNING"))
        return sessionId to runId
    }

    private fun live(sessionId: UUID, runId: UUID): Map<*, *>? = getRun(sessionId, runId).body!!["live"] as Map<*, *>?

    private fun prometheusAnswers(query: String, body: Any) = prometheus.answerQuery("GET", QUERY_PATH, "query=$query", 200, body)

    @Test
    fun `a running run shows the bank's numbers, the clients' counts, the pods and the ledger's duplicates`() {
        val (sessionId, runId) = running()
        reporting.answer("GET", ReportingApi.STATS_PATH.forRun(runId), 200, stats(runId, accepted = 500, posted = 480, rejected = 5))
        prometheusAnswers(UP, prometheus(listOf(pod("ledger", "a:8080") to 1, pod("ledger", "b:8080") to 0, pod("gateway", "c:8081") to 1)))
        prometheusAnswers(PARTITIONS, prometheus(listOf(pod("ledger", "a:8080") to 6)))
        prometheusAnswers(DUPLICATES, duplicates(runId to 3))

        conductor.tick()

        val live = checkNotNull(live(sessionId, runId))
        assertThat(((live["stats"] as Map<*, *>)["totals"] as Map<*, *>)["inFlight"]).isEqualTo(15)
        assertThat((live["clients"] as Map<*, *>)["requested"]).isEqualTo(1000)
        assertThat(live["pods"]).isEqualTo(
            listOf(
                mapOf("job" to "gateway", "instance" to "c:8081", "up" to true, "partitions" to null),
                mapOf("job" to "ledger", "instance" to "a:8080", "up" to true, "partitions" to 6),
                mapOf("job" to "ledger", "instance" to "b:8080", "up" to false, "partitions" to null),
            ),
        )
        assertThat(live["ledgerDuplicatesAtLeast"]).isEqualTo(3)
    }

    @Test
    fun `each part keeps its last numbers while its source cannot be read`() {
        val (sessionId, runId) = running()
        reporting.answer("GET", ReportingApi.STATS_PATH.forRun(runId), 200, stats(runId, accepted = 500))
        prometheusAnswers(UP, prometheus(listOf(pod("ledger", "a:8080") to 1)))
        conductor.tick()

        reporting.answer("GET", ReportingApi.STATS_PATH.forRun(runId), 503)
        prometheus.reset()
        prometheus.status = 503
        conductor.tick()

        val live = checkNotNull(live(sessionId, runId))
        assertThat(((live["stats"] as Map<*, *>)["totals"] as Map<*, *>)["accepted"]).isEqualTo(500)
        assertThat(live["pods"] as List<*>).hasSize(1)
    }

    @Test
    fun `the ledger's duplicates keep the most seen, as an instance that dies takes its count along`() {
        val (sessionId, runId) = running()
        prometheusAnswers(DUPLICATES, duplicates(runId to 9))
        conductor.tick()

        prometheusAnswers(DUPLICATES, duplicates(runId to 4))
        conductor.tick()

        assertThat(live(sessionId, runId)!!["ledgerDuplicatesAtLeast"]).isEqualTo(9)
    }

    @Test
    fun `a run Prometheus has no duplicates of shows zero, not unknown`() {
        val (sessionId, runId) = running()
        prometheusAnswers(DUPLICATES, duplicates(UUID.randomUUID() to 2))

        conductor.tick()

        assertThat(live(sessionId, runId)!!["ledgerDuplicatesAtLeast"]).isEqualTo(0)
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `a failure while reading live numbers is logged, not lost in a future`(output: CapturedOutput) {
        running()
        val noValue = mapOf("status" to "success", "data" to mapOf("resultType" to "vector", "result" to listOf(mapOf("metric" to mapOf("job" to "ledger"), "value" to emptyList<Any>()))))
        prometheusAnswers(UP, noValue)

        conductor.tick()

        assertThat(output.out).contains("Live numbers not read")
    }

    @Test
    fun `without any answer yet the parts are null, and a run past draining has no live numbers`() {
        val (sessionId, runId) = running()
        reporting.status = 503
        prometheus.status = 503
        conductor.tick()

        val live = checkNotNull(live(sessionId, runId))
        assertThat(live["stats"]).isNull()
        assertThat(live["pods"]).isNull()
        assertThat(live["ledgerDuplicatesAtLeast"]).isNull()

        setRun(runId, state = "FAILED")
        assertThat(live(sessionId, runId)).isNull()
    }

    private fun pod(job: String, instance: String) = mapOf("job" to job, "instance" to instance)

    private companion object {
        const val QUERY_PATH = "/api/v1/query"
        const val UP = "up"
        const val PARTITIONS = "ledger_kafka_assigned_partitions"
        const val DUPLICATES = "sum by (run) (ledger_duplicates_total)"
    }
}
