package com.ledgerlab.control.bank;

import com.ledgerlab.control.availability.Freshness;
import com.ledgerlab.control.availability.MaintenanceWindow;
import com.ledgerlab.control.availability.Slo;
import com.ledgerlab.control.chaos.ChaosEvent;
import com.ledgerlab.control.generator.ClientTotals;
import com.ledgerlab.control.generator.Pulse;
import com.ledgerlab.control.metrics.PodView;
import com.ledgerlab.control.periods.PeriodNumbers;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(description = "The bank as control sees it now.")
public record BankView(
    @Schema(description = "When the bank started: control's first tick; null before it.", nullable = true)
    @Nullable Instant startedAt,
    long periodMinutes,
    CurrentPeriod currentPeriod,
    @Schema(description = "Transfers since the bank started: the reconciled periods and the live numbers of those still open.")
    PeriodNumbers totals,
    @Schema(description = "null until the ledger answered once.", nullable = true)
    @Nullable BooksNow books,
    @Schema(description = "null until the generator answered once.", nullable = true)
    @Nullable Clients clients,
    @Schema(description = "null until Prometheus answered once.", nullable = true)
    @Nullable List<PodView> pods,
    Slo slo,
    Freshness freshness,
    @Schema(description = "The maintenance window that is on, else the next one; null if none is declared.", nullable = true)
    @Nullable MaintenanceWindow maintenance,
    Chaos chaos,
    @Schema(description = "When control last read the live parts; null before the first read.", nullable = true)
    @Nullable Instant readAt
) {

    public record CurrentPeriod(
        Instant start,
        Instant end,
        @Schema(description = "When control asks the gateway to close it.")
        Instant closesAt
    ) {
    }

    @Schema(description = "All balances of the bank against the sum its accounts were opened with, read now and then.")
    public record BooksNow(long accounts, long openingTotalCents, long balanceTotalCents, boolean balanced, Instant readAt) {
    }

    @Schema(description = "What the clients count: since the generator started, and in total since the bank started.")
    public record Clients(
        @Schema(description = "When the generator started sending; null before. The counts other than total run from it.", nullable = true)
        @Nullable Instant since,
        int baseTps,
        int targetTps,
        @Schema(description = "null without a pulse.", nullable = true)
        @Nullable Pulse pulse,
        long requested,
        long acknowledged,
        @Schema(description = "Transfers whose client stopped trying: refused for good, or without an answer it could use.")
        long gaveUp,
        @Schema(description = "Transfers the gateway refused as busy (503) at least once, retried or not.")
        long refused,
        long retries,
        long inFlight,
        @Schema(description = "Since the bank started; null while the generator cannot read its journal.", nullable = true)
        @Nullable ClientTotals total,
        @Schema(description = "Whether clients sent before this generator started: the counts other than total then cover only its part.")
        boolean restarted
    ) {
    }

    public record Chaos(
        @Schema(description = "Whether the chaos button works in this deployment.")
        boolean available,
        @Schema(description = "Seconds until chaos may be used again; 0 when it may be used now.")
        long readyInSeconds,
        @Schema(description = "The last crashes, in order.")
        List<ChaosEvent> recent
    ) {
    }
}
