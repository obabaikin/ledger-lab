package com.ledgerlab.control

import com.ledgerlab.control.RunFixtures.simulation
import com.ledgerlab.control.generator.GeneratorApi
import com.ledgerlab.control.http.forRun
import com.ledgerlab.control.ledger.LedgerReadiness
import com.ledgerlab.control.runs.Conductor
import com.ledgerlab.control.runs.RunQueue
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Control's queries against a real Prometheus scraping fake services: a canned answer cannot show what PromQL does (E97). */
class RealPrometheusTest : IntegrationTest() {

    @Autowired lateinit var conductor: Conductor
    @Autowired lateinit var queue: RunQueue
    @Autowired lateinit var readiness: LedgerReadiness

    private fun running(): Pair<UUID, UUID> {
        val sessionId = sessionOf(invite())
        val runId = UUID.fromString(start(sessionId).body!!["runId"] as String)
        queue.advance(bankReady = true)
        jdbc.sql("UPDATE runs SET state = 'RUNNING', started_at = clock_timestamp() WHERE run_id = :id").param("id", runId).update()
        generator.answer("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), 200, simulation(runId, "RUNNING"))
        return sessionId to runId
    }

    private fun live(sessionId: UUID, runId: UUID) = getRun(sessionId, runId).body!!["live"] as Map<*, *>

    @Test
    fun `a ledger pod shows its partitions next to whether it answered, other services none`() {
        val (sessionId, runId) = running()
        exporter.serve(LEDGER, "ledger_kafka_assigned_partitions 3")
        exporter.serve(SECOND_LEDGER, "")
        exporter.awaitScrapes(SCRAPES_TO_SETTLE)

        conductor.tick()

        val pods = (live(sessionId, runId)["pods"] as List<*>).map { it as Map<*, *> }.associateBy { it["job"] }
        assertThat(pods.getValue(LEDGER).let { it["up"] to it["partitions"] }).isEqualTo(true to 3)
        assertThat(pods.getValue(SECOND_LEDGER).let { it["up"] to it["partitions"] }).isEqualTo(true to null)
    }

    @Test
    fun `the pods are the bank's services only, not the exporters Prometheus scrapes beside them`() {
        val (sessionId, runId) = running()
        exporter.serve(LEDGER, "")
        exporter.serve(SECOND_LEDGER, "")
        exporter.serve(NODE_EXPORTER, "node_load1 0.5")
        exporter.awaitScrapes(SCRAPES_TO_SETTLE)

        conductor.tick()

        assertThat((live(sessionId, runId)["pods"] as List<*>).map { (it as Map<*, *>)["job"] })
            .contains(LEDGER, SECOND_LEDGER).doesNotContain(NODE_EXPORTER)
    }

    @Test
    fun `the ledger's duplicates of a run are summed over its instances, apart from a run beside it`() {
        val (sessionId, runId) = running()
        val other = UUID.randomUUID()
        exporter.serve(LEDGER, "ledger_duplicates_total{run=\"$runId\"} 4\nledger_duplicates_total{run=\"$other\"} 50")
        exporter.serve(SECOND_LEDGER, "ledger_duplicates_total{run=\"$runId\"} 3")
        exporter.awaitScrapes(SCRAPES_TO_SETTLE)

        conductor.tick()

        assertThat(live(sessionId, runId)["ledgerDuplicatesAtLeast"]).isEqualTo(7)
    }

    @Test
    fun `a killed instance's partitions stop counting, so the ledger is not ready until the live ones hold all`() {
        exporter.serve(LEDGER, "ledger_kafka_assigned_partitions 2")
        exporter.serve(SECOND_LEDGER, "ledger_kafka_assigned_partitions 2")
        killedExporter.serve(KILLED_LEDGER, "ledger_kafka_assigned_partitions ${props.ledger.transferPartitions - 4}")
        killedExporter.awaitScrapes(SCRAPES_TO_SETTLE)
        exporter.awaitScrapes(SCRAPES_TO_SETTLE)
        assertThat(readiness.isReady()).isTrue()

        killedExporter.close()
        exporter.awaitScrapes(SCRAPES_TO_SETTLE)
        assertThat(readiness.isReady()).isFalse()

        exporter.serve(SECOND_LEDGER, "ledger_kafka_assigned_partitions ${props.ledger.transferPartitions - 2}")
        exporter.awaitScrapes(SCRAPES_TO_SETTLE)
        assertThat(readiness.isReady()).isTrue()
    }

    /** Serves each job's metrics as text and counts the scrapes, so a test knows Prometheus has seen what it serves. */
    class FakeExporter(private val jobs: List<String>) : AutoCloseable {
        private val bodies = ConcurrentHashMap<String, String>()
        private val scrapes = AtomicInteger()

        private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/") { exchange ->
                val body = bodies[exchange.requestURI.path.removePrefix("/")].orEmpty().toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                exchange.sendResponseHeaders(200, body.size.toLong().takeIf { it > 0 } ?: -1)
                if (body.isNotEmpty()) exchange.responseBody.write(body)
                exchange.close()
                scrapes.incrementAndGet()
            }
            executor = Executors.newVirtualThreadPerTaskExecutor()
            start()
        }

        val port: Int = server.address.port

        fun serve(job: String, metrics: String) {
            bodies[job] = metrics.trimIndent() + "\n"
        }

        fun awaitScrapes(perJob: Int, within: Duration = SCRAPE_INTERVAL.multipliedBy(perJob.toLong() + 2)) {
            val until = scrapes.get() + perJob * jobs.size
            val deadline = Instant.now() + within
            while (scrapes.get() < until) {
                check(Instant.now() < deadline) { "Prometheus did not scrape: ${scrapes.get()} of $until" }
                Thread.sleep(POLL)
            }
        }

        override fun close() = server.stop(0)
    }

    companion object {
        private const val LEDGER = "ledger"
        private const val SECOND_LEDGER = "ledger-b"
        /** Scraped without the bank's label, as the chart's exporters are. */
        private const val NODE_EXPORTER = "node-exporter"
        private val JOBS = listOf(LEDGER, SECOND_LEDGER, NODE_EXPORTER)
        /** A ledger instance the test kills by closing its exporter. */
        private const val KILLED_LEDGER = "ledger-killed"
        private const val PROMETHEUS_PORT = 9090
        private val SCRAPE_INTERVAL: Duration = Duration.ofSeconds(1)
        private val POLL: Duration = Duration.ofMillis(20)
        /** Prometheus starts scraping new targets only seconds after it is ready. */
        private val TARGETS_DISCOVERED: Duration = Duration.ofSeconds(30)
        /** A sample is queryable once the next scrape came. */
        private const val SCRAPES_TO_SETTLE = 2

        val exporter = FakeExporter(JOBS)
        val killedExporter = FakeExporter(listOf(KILLED_LEDGER))

        private val prometheus: GenericContainer<*> by lazy {
            Testcontainers.exposeHostPorts(exporter.port, killedExporter.port)
            val targets = JOBS.map { it to exporter.port } + (KILLED_LEDGER to killedExporter.port)
            val jobs = targets.joinToString("\n") { (job, port) ->
                """
                |  - job_name: $job
                |    metrics_path: /$job
                |    static_configs:
                |      - targets: ["host.testcontainers.internal:$port"]
                |        ${if (job == NODE_EXPORTER) "labels: {}" else "labels: {bank_service: \"true\"}"}
                """.trimMargin()
            }
            val config = "global:\n  scrape_interval: ${SCRAPE_INTERVAL.toSeconds()}s\n  scrape_timeout: 800ms\nscrape_configs:\n$jobs\n"
            GenericContainer("prom/prometheus:v3.5.0")
                .withCopyToContainer(Transferable.of(config), "/etc/prometheus/prometheus.yml")
                .withExposedPorts(PROMETHEUS_PORT)
                .waitingFor(Wait.forHttp("/-/ready").forPort(PROMETHEUS_PORT))
                .also {
                    it.start()
                    exporter.awaitScrapes(1, within = TARGETS_DISCOVERED)
                }
        }

        @JvmStatic
        @DynamicPropertySource
        fun realPrometheus(registry: DynamicPropertyRegistry) {
            registry.add("control.prometheus.base-url") { "http://${prometheus.host}:${prometheus.getMappedPort(PROMETHEUS_PORT)}" }
        }
    }
}
