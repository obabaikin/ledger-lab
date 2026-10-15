package com.ledgerlab.control

import java.util.UUID

/** Answers of the services in the shape control reads; each test changes only what it is about. */
object RunFixtures {
    fun simulation(runId: UUID, state: String) = mapOf(
        "runId" to runId, "state" to state, "finishedAt" to null, "requested" to 1000, "acknowledged" to 990,
        "failed" to 5, "unknown" to 5, "retries" to 3, "duplicatesSent" to 2,
    )

    fun summary(runId: UUID, accepted: Long = 995, posted: Long = 980, rejected: Long = 15, reasons: Map<String, Long> = mapOf("BLOCKED_ACCOUNT" to 15L)) = mapOf(
        "runId" to runId, "accepted" to accepted, "published" to accepted, "posted" to posted, "rejected" to rejected,
        "rejectionsByReason" to reasons, "latencyP50Ms" to 12.0, "latencyP99Ms" to 480.5,
    )

    fun books(runId: UUID, posted: Long = 980, rejected: Long = 15, balanced: Boolean = true, accounts: Long = 100, reasons: Map<String, Long> = mapOf("BLOCKED_ACCOUNT" to 15L)) = mapOf(
        "runId" to runId, "accounts" to accounts, "balanced" to balanced,
        "failedChecks" to if (balanced) emptyList() else listOf("TOTAL_BALANCE_CONSERVED"),
        "postedTransfers" to posted, "rejectedTransfers" to rejected, "rejectionsByReason" to reasons,
        "openingTotalCents" to accounts * 100_000, "balanceTotalCents" to accounts * 100_000,
        "debitsCents" to posted * 100, "creditsCents" to posted * 100,
    )

    fun stats(runId: UUID, accepted: Long = 995, posted: Long = 980, rejected: Long = 15, commandsSeen: Long = accepted) = mapOf(
        "runId" to runId, "at" to "2026-10-13T10:00:05Z",
        "totals" to mapOf(
            "accepted" to accepted, "commandsSeen" to commandsSeen, "posted" to posted, "rejected" to rejected, "inFlight" to accepted - posted - rejected,
            "postedAmountCents" to posted * 100, "rejectedByReason" to mapOf("BLOCKED_ACCOUNT" to rejected),
        ),
        "bankLatency" to mapOf("p50Ms" to 14.0, "p99Ms" to 490.0),
        "series" to listOf(
            mapOf(
                "second" to "2026-10-13T10:00:00Z", "accepted" to accepted, "posted" to posted, "rejected" to rejected,
                "postedAmountCents" to posted * 100, "bankP50Ms" to 14.0, "bankP99Ms" to 490.0,
            ),
        ),
    )

    fun duplicates(vararg runs: Pair<UUID, Long>) = prometheus(runs.map { (runId, count) -> mapOf("run" to runId.toString()) to count })

    /** An instant query's answer: one sample per label set. */
    fun prometheus(samples: List<Pair<Map<String, String>, Number>>) = mapOf(
        "status" to "success",
        "data" to mapOf("resultType" to "vector", "result" to samples.map { (labels, value) -> mapOf("metric" to labels, "value" to listOf(1_760_000_000.0, value.toString())) }),
    )

    private fun ids(vararg ids: UUID) = mapOf("count" to ids.size, "transferIds" to ids.toList())

    fun clientReport(runId: UUID, balanced: Boolean = true, statementFinal: Boolean = true) = mapOf(
        "runId" to runId, "balanced" to balanced, "statementFinal" to statementFinal,
        "journal" to mapOf("requested" to 1000, "acknowledged" to 990, "failed" to 5, "unknown" to 5, "acknowledgedAmountCents" to 99000),
        "statement" to mapOf("rows" to 995, "amountCents" to 99500, "byStatus" to mapOf("POSTED" to 980, "REJECTED" to 15)),
        "breaks" to mapOf(
            "notInJournal" to ids(), "acknowledgedNotInStatement" to if (balanced) ids() else ids(UUID.randomUUID()),
            "failedButInStatement" to ids(), "payloadMismatch" to ids(), "statusMismatch" to ids(),
        ),
        "unknownResolved" to mapOf("accepted" to ids(UUID.randomUUID()), "notAccepted" to ids()),
    )
}
