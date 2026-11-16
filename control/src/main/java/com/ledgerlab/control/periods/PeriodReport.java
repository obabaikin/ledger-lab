package com.ledgerlab.control.periods;

import com.ledgerlab.control.chaos.ChaosEvent;
import com.ledgerlab.control.generator.ClientReconciliationReport;
import com.ledgerlab.control.ledger.Books;
import com.ledgerlab.control.ledger.PeriodCheck;
import com.ledgerlab.control.reporting.PeriodStats;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

@Schema(description = "The verdict of a period and what it rests on; the bank deletes the transfers themselves after its retention.")
public record PeriodReport(
    Verdict verdict,
    @Schema(description = "What disagreed; empty for BALANCED.")
    List<Mismatch> mismatches,
    GatewayCounts gateway,
    LedgerCounts ledger,
    @Schema(description = "Rejections by reason, as the ledger decided them.")
    Map<String, Long> rejectionsByReason,
    PeriodCheck ledgerCheck,
    @Schema(description = "All balances of the bank when the period was reconciled.")
    Books books,
    ClientReconciliationReport client,
    @Schema(description = "null if the reporting service could not be read.", nullable = true)
    @Nullable PeriodStats stats,
    @Schema(description = "What the report could not show; does not decide the verdict.")
    List<PeriodNote> notes,
    @Schema(description = "Ledger crashes within the period, in order; empty if none.")
    List<ChaosEvent> chaos,
    @Schema(description = "null in reports made before control kept the load.", nullable = true)
    @Nullable PeriodLoad load
) {

    public PeriodReport {
        Objects.requireNonNull(verdict, "verdict");
        mismatches = List.copyOf(Objects.requireNonNull(mismatches, "mismatches"));
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(ledger, "ledger");
        rejectionsByReason = Map.copyOf(Objects.requireNonNull(rejectionsByReason, "rejectionsByReason"));
        Objects.requireNonNull(ledgerCheck, "ledgerCheck");
        Objects.requireNonNull(books, "books");
        Objects.requireNonNull(client, "client");
        notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
        chaos = List.copyOf(Objects.requireNonNull(chaos, "chaos"));
    }
}
