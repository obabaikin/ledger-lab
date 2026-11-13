package com.ledgerlab.control;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class Fixtures {

    static final long ACCEPTED = 1000;
    static final long POSTED = 900;
    static final long REJECTED = 100;
    static final long POSTED_AMOUNT_CENTS = 450_000;
    static final long ACCOUNTS = 100_000;
    static final long OPENING_TOTAL_CENTS = 110_000_000_000L;
    static final Map<String, Long> REASONS = Map.of("INSUFFICIENT_FUNDS", 60L, "BLOCKED_ACCOUNT", 40L);

    record Sample(Map<String, String> labels, Number value) {
    }

    private Fixtures() {
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

    static Map<String, Object> prometheusVector(Sample... samples) {
        var result = List.of(samples).stream()
            .map(it -> mapOf("metric", it.labels(), "value", List.of(1_760_000_000, it.value().toString())))
            .toList();
        return mapOf("status", "success", "data", mapOf("resultType", "vector", "result", result));
    }

    static Map<String, Object> closed(Instant periodStart, long entryCount) {
        return mapOf("periodStart", periodStart, "closedAt", periodStart.plusSeconds(3660), "entryCount", entryCount);
    }

    static Map<String, Object> summary(Instant periodStart) {
        return summary(periodStart, ACCEPTED, POSTED, REJECTED, REASONS);
    }

    static Map<String, Object> summary(Instant periodStart, long accepted, long posted, long rejected, Map<String, Long> reasons) {
        return mapOf(
            "periodStart", periodStart, "accepted", accepted, "published", accepted, "posted", posted, "rejected", rejected,
            "rejectionsByReason", reasons);
    }

    static Map<String, Object> reconciliation(Instant periodStart) {
        return reconciliation(periodStart, POSTED, REJECTED, REASONS, check(true));
    }

    static Map<String, Object> reconciliation(Instant periodStart, long posted, long rejected, Map<String, Long> reasons, Map<String, Object> check) {
        return mapOf(
            "periodStart", periodStart, "postedTransfers", posted, "rejectedTransfers", rejected, "rejectionsByReason", reasons,
            "poolPostings", 70, "check", check);
    }

    static Map<String, Object> check(boolean balanced) {
        return mapOf(
            "journalEntries", 2 * POSTED, "debitsCents", POSTED_AMOUNT_CENTS, "creditsCents", POSTED_AMOUNT_CENTS,
            "transfersWithInvalidJournal", 0, "invalidJournalExamples", List.of(), "accountsCompared", true,
            "accountsOutOfBalance", balanced ? 0 : 1,
            "accountExamples", balanced ? List.of() : List.of(mapOf("accountNo", 17, "journalCents", -500, "balanceChangeCents", -400)),
            "failedChecks", balanced ? List.of() : List.of("EVERY_ACCOUNT_MATCHES_JOURNAL"), "balanced", balanced);
    }

    static Map<String, Object> books(boolean balanced) {
        return mapOf(
            "accounts", ACCOUNTS, "openingTotalCents", OPENING_TOTAL_CENTS, "balanceTotalCents", OPENING_TOTAL_CENTS - (balanced ? 0 : 100),
            "balanced", balanced);
    }

    static Map<String, Object> clientReconciliation(Instant periodStart, String state) {
        return mapOf("periodStart", periodStart, "state", state, "failure", null, "report", "DONE".equals(state) ? clientReport(periodStart) : null);
    }

    static Map<String, Object> clientReport(Instant periodStart) {
        return clientReport(periodStart, true, List.of());
    }

    /** The clients were told of these transfers, and the statement does not list them. */
    static Map<String, Object> clientReport(Instant periodStart, boolean balanced, List<UUID> acknowledgedNotInStatement) {
        var none = mapOf("count", 0, "transferIds", List.of());
        var noSides = mapOf("count", 0, "examples", List.of());
        return mapOf(
            "periodStart", periodStart, "balanced", balanced, "statementFinal", true, "acceptedByJournal", ACCEPTED,
            "statement", mapOf("rows", ACCEPTED, "amountCents", POSTED_AMOUNT_CENTS, "byStatus", Map.of("POSTED", POSTED, "REJECTED", REJECTED), "unknownToClient", 0),
            "breaks", mapOf(
                "acknowledgedNotInStatement", mapOf("count", acknowledgedNotInStatement.size(), "transferIds", acknowledgedNotInStatement),
                "failedButInStatement", none, "notInJournal", none, "payloadMismatch", noSides, "statusMismatch", noSides),
            "unknownResolved", mapOf("accepted", 2, "notAccepted", 1));
    }

    static Map<String, Object> clientDone(Instant periodStart, Map<String, Object> report) {
        return mapOf("periodStart", periodStart, "state", "DONE", "failure", null, "report", report);
    }

    static Map<String, Object> stats(Instant periodStart) {
        return stats(periodStart, ACCEPTED, POSTED, REJECTED);
    }

    static Map<String, Object> stats(Instant periodStart, long accepted, long posted, long rejected) {
        return mapOf(
            "periodStart", periodStart, "at", periodStart.plusSeconds(3700), "accepted", accepted, "commandsSeen", accepted, "posted", posted,
            "rejected", rejected, "postedAmountCents", POSTED_AMOUNT_CENTS, "rejectionsByReason", REASONS);
    }

    /** Since the bank started: more than this generator has sent since its own start. */
    static final Map<String, Object> TOTAL = mapOf("sent", 917_000, "acknowledged", 916_000, "gaveUp", 600, "unanswered", 400);

    static Map<String, Object> load() {
        return mapOf(
            "startedAt", "2026-11-10T08:00:00Z", "baseTps", 400, "targetTps", 400, "pulse", null, "requested", 5000, "acknowledged", 4980,
            "failed", 3, "unknown", 4, "retries", 9, "refused", 6, "inFlight", 13, "duplicatesSent", 2, "posted", 4500, "rejected", 480,
            "rejectedByReason", Map.of(), "skipped", 0, "notJournaled", 0, "total", TOTAL, "restarted", true);
    }
}
