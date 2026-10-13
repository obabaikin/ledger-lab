package com.ledgerlab.control.metrics

import com.ledgerlab.control.generator.GeneratorClient
import com.ledgerlab.control.http.Reply
import com.ledgerlab.control.reporting.ReportingClient
import com.ledgerlab.control.reporting.RunStats
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToLong

@Schema(description = "What the clients counted, from the generator's status.")
data class ClientCounts(
    val requested: Long,
    val acknowledged: Long,
    val failed: Long,
    val unknown: Long,
    val retries: Long,
    @field:Schema(description = "Second presses of the same transfer; the gateway answers them without a new transfer.")
    val duplicatesSent: Long,
)

@Schema(description = "One instance of a service, from Prometheus; approximate, a scrape or two behind.")
data class PodView(
    val job: String,
    val instance: String,
    @field:Schema(description = "The last scrape got an answer.")
    val up: Boolean,
    @field:Schema(description = "Partitions the ledger instance consumes; null for other services.")
    val partitions: Int?,
)

@Schema(description = "Live numbers of a running or draining run; each part is the last one read and keeps it while its source cannot be read.")
data class LiveView(
    @field:Schema(description = "The bank's numbers from its events; null until the reporting service answered once.")
    val stats: RunStats?,
    @field:Schema(description = "null until the generator answered once.")
    val clients: ClientCounts?,
    @field:Schema(description = "null until Prometheus answered once.")
    val pods: List<PodView>?,
    @field:Schema(description = "Redeliveries the ledger skipped, at least this many: from monitoring, and a killed instance takes its count along. Not part of the verdict.")
    val ledgerDuplicatesAtLeast: Long?,
    @field:Schema(description = "When control last read any part, by its own clock.")
    val readAt: Instant,
)

@Component
class LiveRuns(
    private val reporting: ReportingClient,
    private val generator: GeneratorClient,
    private val prometheus: PrometheusClient,
) {
    private val views = ConcurrentHashMap<UUID, LiveView>()
    private val duplicatesSeen = ConcurrentHashMap<UUID, Long>()

    fun of(runId: UUID): LiveView? = views[runId]

    fun ledgerDuplicatesAtLeast(runId: UUID): Long? = duplicatesSeen[runId]

    fun refresh(runIds: Collection<UUID>) {
        views.keys.retainAll(runIds.toSet())
        duplicatesSeen.keys.retainAll(runIds.toSet())
        if (runIds.isEmpty()) return
        Executors.newVirtualThreadPerTaskExecutor().use { reads ->
            val pods = reads.submit<List<PodView>?> { pods() }
            val duplicates = reads.submit<Map<UUID, Long>?> { duplicates() }
            val perRun = runIds.associateWith { runId ->
                reads.submit<Pair<RunStats?, ClientCounts?>> { stats(runId) to clients(runId) }
            }
            val podsNow = pods.get()
            // A run without a series has no duplicates yet: the ledger registers its counter on the first one.
            duplicates.get()?.let { counts -> runIds.forEach { duplicatesSeen.merge(it, counts[it] ?: 0, ::maxOf) } }
            val now = Instant.now()
            perRun.forEach { (runId, read) ->
                val (stats, clients) = read.get()
                views.compute(runId) { _, last ->
                    LiveView(
                        stats = stats ?: last?.stats,
                        clients = clients ?: last?.clients,
                        pods = podsNow ?: last?.pods,
                        ledgerDuplicatesAtLeast = duplicatesSeen[runId],
                        readAt = now,
                    )
                }
            }
        }
    }

    private fun stats(runId: UUID): RunStats? = (reporting.stats(runId) as? Reply.Answered)?.body

    private fun clients(runId: UUID): ClientCounts? =
        (generator.status(runId) as? Reply.Answered)?.body?.let {
            ClientCounts(it.requested, it.acknowledged, it.failed, it.unknown, it.retries, it.duplicatesSent)
        }

    private fun pods(): List<PodView>? {
        val up = prometheus.query(UP) ?: return null
        val partitions = prometheus.query(PARTITIONS)?.associate { it.target() to it.number.roundToLong().toInt() }.orEmpty()
        return up.map { sample ->
            val (job, instance) = sample.target()
            PodView(job, instance, sample.number == UP_VALUE, partitions[job to instance])
        }.sortedWith(compareBy({ it.job }, { it.instance }))
    }

    private fun PrometheusSample.target(): Pair<String, String> = metric[JOB].orEmpty() to metric[INSTANCE].orEmpty()

    /** A sum over the instances alive now; the most ever seen is kept, as an instance that dies takes its count along. */
    private fun duplicates(): Map<UUID, Long>? =
        prometheus.query(DUPLICATES)?.mapNotNull { sample ->
            sample.metric[RUN]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { it to sample.number.roundToLong() }
        }?.toMap()

    private companion object {
        const val UP = "up"
        const val PARTITIONS = "ledger_kafka_assigned_partitions"
        const val DUPLICATES = "sum by (run) (ledger_duplicates_total)"
        const val INSTANCE = "instance"
        const val JOB = "job"
        const val RUN = "run"
        const val UP_VALUE = 1.0
    }
}
