package com.ledgerlab.control.runs;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.ledgerlab.control.generator.JournalTotals;
import com.ledgerlab.control.generator.ReconciliationBreaks;
import com.ledgerlab.control.generator.UnknownResolved;
import com.ledgerlab.control.ledger.ReconciliationCheck;
import com.ledgerlab.control.reporting.RunStats;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

@Schema(description = "The verdict of a run and what it rests on.")
public record RunReport(
    RunResult result,
    @Schema(description = "What the verdict could not check, with or without breaks; empty if nothing was missing.")
    List<String> reasons,
    @Schema(description = "The ledger's books: balances, journal and transfer log agree.")
    boolean ledgerBalanced,
    List<ReconciliationCheck> ledgerFailedChecks,
    @Schema(description = "Gateway and ledger count the same posted and rejected transfers and reasons; null if the drain did not finish.", nullable = true)
    @Nullable Boolean bankViewsAgree,
    @Schema(description = "The clients' journal matches the statement; null without a client reconciliation.", nullable = true)
    @Nullable Boolean clientBalanced,
    @Schema(description = "No statement line was still in flight; null without a client reconciliation.", nullable = true)
    @Nullable Boolean statementFinal,
    @Schema(description = "What the clients were told; null without a client reconciliation.", nullable = true)
    @Nullable JournalTotals client,
    GatewayCounts gateway,
    LedgerCounts ledger,
    @Schema(description = "Bank latency p50, ms: accepted to result known, by the gateway's clock; null without results.", nullable = true)
    @Nullable Double bankLatencyP50Ms,
    @Schema(description = "Bank latency p99, ms, as bankLatencyP50Ms.", nullable = true)
    @Nullable Double bankLatencyP99Ms,
    @Schema(description = "Rejections by reason, as the ledger decided them.")
    Map<String, Long> rejectionsByReason,
    @Schema(description = "Discrepancies between journal and statement, by kind, with up to 1000 ids each.", nullable = true)
    @Nullable ReconciliationBreaks breaks,
    @Schema(description = "Transfers the clients did not know the outcome of, settled by the statement.", nullable = true)
    @Nullable UnknownResolved unknownResolved,
    @Schema(description = "Client latency p50, ms: a waiting client's first press to a final status, exact over the run; presses whose wait ran out are left out; null without one.", nullable = true)
    @Nullable Double clientLatencyP50Ms,
    @Schema(description = "Client latency p99, ms, as clientLatencyP50Ms.", nullable = true)
    @Nullable Double clientLatencyP99Ms,
    @Schema(description = "The run's numbers from the bank's events, for the page; null if the reporting service could not be read.", nullable = true)
    @Nullable RunStats stats,
    @Schema(description = "The projection counts what the books count: commands seen as accepted, posted, rejected; null without stats.", nullable = true)
    @Nullable Boolean projectionMatchesBooks,
    @Schema(description = "Redeliveries the ledger skipped, at least this many, from monitoring; null if never read.", nullable = true)
    @Nullable Long ledgerDuplicatesAtLeast,
    @Schema(description = "What the report could not show; does not decide the verdict.")
    List<String> notes,
    @Schema(description = "Ledger crashes while the run was active, in order; empty if none.")
    List<ChaosInRun> chaos
) {
    public RunReport {
        Objects.requireNonNull(result, "result");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        ledgerFailedChecks = List.copyOf(Objects.requireNonNull(ledgerFailedChecks, "ledgerFailedChecks"));
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(rejectionsByReason, "rejectionsByReason");
        notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
        chaos = List.copyOf(Objects.requireNonNull(chaos, "chaos"));
    }

    @JsonCreator
    static RunReport read(
        RunResult result,
        List<String> reasons,
        boolean ledgerBalanced,
        List<ReconciliationCheck> ledgerFailedChecks,
        @Nullable Boolean bankViewsAgree,
        @Nullable Boolean clientBalanced,
        @Nullable Boolean statementFinal,
        @Nullable JournalTotals client,
        GatewayCounts gateway,
        LedgerCounts ledger,
        @Nullable Double bankLatencyP50Ms,
        @Nullable Double bankLatencyP99Ms,
        Map<String, Long> rejectionsByReason,
        @Nullable ReconciliationBreaks breaks,
        @Nullable UnknownResolved unknownResolved,
        @Nullable Double clientLatencyP50Ms,
        @Nullable Double clientLatencyP99Ms,
        @Nullable RunStats stats,
        @Nullable Boolean projectionMatchesBooks,
        @Nullable Long ledgerDuplicatesAtLeast,
        @Nullable List<String> notes,
        @Nullable List<ChaosInRun> chaos
    ) {
        return new RunReport(
            result, reasons, ledgerBalanced, ledgerFailedChecks, bankViewsAgree, clientBalanced, statementFinal, client, gateway, ledger,
            bankLatencyP50Ms, bankLatencyP99Ms, rejectionsByReason, breaks, unknownResolved, clientLatencyP50Ms, clientLatencyP99Ms,
            stats, projectionMatchesBooks, ledgerDuplicatesAtLeast, notes != null ? notes : List.of(), chaos != null ? chaos : List.of());
    }
}
