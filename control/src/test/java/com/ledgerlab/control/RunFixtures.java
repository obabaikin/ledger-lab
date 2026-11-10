package com.ledgerlab.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Answers of the services in the shape control reads; each test changes only what it is about. */
final class RunFixtures {
    /** Spelled out rather than taken from control, so a changed query fails the tests. */
    static final String GATEWAY_ALL_WARM = "min(gateway_warm_up_complete) * (count(gateway_warm_up_complete) == bool count(up{job=\"gateway\"}))";

    static final long ACCEPTED = 995;
    static final long POSTED = 980;
    static final long REJECTED = 15;
    static final long ACCOUNTS = 100;
    static final Map<String, Long> REASONS = Map.of("BLOCKED_ACCOUNT", 15L);

    record Sample(Map<String, String> labels, Number value) {
    }

    private RunFixtures() {
    }

    static Map<String, Object> mapOf(Object... keysAndValues) {
        var map = new LinkedHashMap<String, Object>();
        for (var i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    static Sample sample(Map<String, String> labels, Number value) {
        return new Sample(labels, value);
    }

    static Map<String, Object> simulation(UUID runId, String state) {
        return mapOf(
            "runId", runId, "state", state, "finishedAt", null, "requested", 1000, "acknowledged", 990,
            "failed", 5, "unknown", 5, "retries", 3, "duplicatesSent", 2);
    }

    static Map<String, Object> summary(UUID runId) {
        return summary(runId, POSTED, REJECTED);
    }

    static Map<String, Object> summary(UUID runId, long posted, long rejected) {
        return summary(runId, ACCEPTED, posted, rejected, REASONS);
    }

    static Map<String, Object> summary(UUID runId, long accepted, long posted, long rejected, Map<String, Long> reasons) {
        return mapOf(
            "runId", runId, "accepted", accepted, "published", accepted, "posted", posted, "rejected", rejected,
            "rejectionsByReason", reasons, "latencyP50Ms", 12.0, "latencyP99Ms", 480.5);
    }

    static Map<String, Object> books(UUID runId) {
        return books(runId, POSTED, REJECTED);
    }

    static Map<String, Object> books(UUID runId, long posted, long rejected) {
        return books(runId, posted, rejected, true, ACCOUNTS, REASONS);
    }

    static Map<String, Object> books(UUID runId, long posted, long rejected, boolean balanced, long accounts, Map<String, Long> reasons) {
        return mapOf(
            "runId", runId, "accounts", accounts, "balanced", balanced,
            "failedChecks", balanced ? List.of() : List.of("TOTAL_BALANCE_CONSERVED"),
            "postedTransfers", posted, "rejectedTransfers", rejected, "rejectionsByReason", reasons,
            "openingTotalCents", accounts * 100_000, "balanceTotalCents", accounts * 100_000,
            "debitsCents", posted * 100, "creditsCents", posted * 100);
    }

    static Map<String, Object> stats(UUID runId) {
        return stats(runId, ACCEPTED, POSTED, REJECTED);
    }

    static Map<String, Object> stats(UUID runId, long accepted, long posted, long rejected) {
        return stats(runId, accepted, posted, rejected, accepted);
    }

    static Map<String, Object> stats(UUID runId, long accepted, long posted, long rejected, long commandsSeen) {
        return mapOf(
            "runId", runId, "at", "2026-10-13T10:00:05Z",
            "totals", mapOf(
                "accepted", accepted, "commandsSeen", commandsSeen, "posted", posted, "rejected", rejected, "inFlight", accepted - posted - rejected,
                "postedAmountCents", posted * 100, "rejectedByReason", mapOf("BLOCKED_ACCOUNT", rejected)),
            "bankLatency", mapOf("p50Ms", 14.0, "p99Ms", 490.0),
            "series", List.of(
                mapOf(
                    "second", "2026-10-13T10:00:00Z", "accepted", accepted, "posted", posted, "rejected", rejected,
                    "postedAmountCents", posted * 100, "bankP50Ms", 14.0, "bankP99Ms", 490.0)));
    }

    static Map<String, Object> duplicates(UUID runId, long count) {
        return prometheus(List.of(sample(Map.of("run", runId.toString()), count)));
    }

    /** An instant query's answer: one sample per label set. */
    static Map<String, Object> prometheus(List<Sample> samples) {
        return mapOf(
            "status", "success",
            "data", mapOf(
                "resultType", "vector",
                "result", samples.stream().map(it -> mapOf("metric", it.labels(), "value", List.of(1_760_000_000.0, it.value().toString()))).toList()));
    }

    private static Map<String, Object> ids(UUID... ids) {
        return mapOf("count", ids.length, "transferIds", List.of(ids));
    }

    static Map<String, Object> clientReport(UUID runId) {
        return clientReport(runId, true, true);
    }

    static Map<String, Object> clientReport(UUID runId, boolean balanced, boolean statementFinal) {
        return clientReport(runId, balanced, statementFinal, 0);
    }

    static Map<String, Object> clientReport(UUID runId, boolean balanced, boolean statementFinal, long refused) {
        return mapOf(
            "runId", runId, "balanced", balanced, "statementFinal", statementFinal,
            "journal", mapOf("requested", 1000, "acknowledged", 990, "failed", 5, "unknown", 5, "acknowledgedAmountCents", 99000, "refused", refused),
            "statement", mapOf("rows", 995, "amountCents", 99500, "byStatus", mapOf("POSTED", 980, "REJECTED", 15)),
            "breaks", mapOf(
                "notInJournal", ids(), "acknowledgedNotInStatement", balanced ? ids() : ids(UUID.randomUUID()),
                "failedButInStatement", ids(), "payloadMismatch", ids(), "statusMismatch", ids()),
            "unknownResolved", mapOf("accepted", ids(UUID.randomUUID()), "notAccepted", ids()));
    }
}
