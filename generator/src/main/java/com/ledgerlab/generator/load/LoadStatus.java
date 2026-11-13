package com.ledgerlab.generator.load;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.journal.ClientTotals;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

@Schema(description = "The clients' load now, what became of their transfers since this generator started, and in total since the bank started.")
public record LoadStatus(
    @Schema(description = "When this generator began to send; null while the load is off. The counts start from zero with it.", nullable = true)
    @Nullable Instant startedAt,
    @Schema(description = "New transfers a second without a pulse.")
    int baseTps,
    @Schema(description = "New transfers a second now, the highest pulse included.")
    int targetTps,
    @Schema(description = "The highest pulse in effect; null without one.", nullable = true)
    @Nullable Pulse pulse,
    @Schema(description = "Transfers the clients set out to send.")
    long requested,
    @Schema(description = "Transfers the bank took (any 2xx).")
    long acknowledged,
    @Schema(description = "Of the clients that waited for the final status: posted.")
    long posted,
    @Schema(description = "Of the clients that waited for the final status: rejected by the bank's rules.")
    long rejected,
    Map<RejectReason, Long> rejectedByReason,
    @Schema(description = "The client gave up and the bank does not have the transfer: refused on every attempt, or never reached.")
    long failed,
    @Schema(description = "The client gave up without knowing whether the bank has the transfer.")
    long unknown,
    long retries,
    @Schema(description = "Transfers the gateway refused as busy (503) at least once, retried or not; each counts once.")
    long refused,
    @Schema(description = "Second sends of the same transfer, as a user pressing twice; not new transfers.")
    long duplicatesSent,
    @Schema(description = "Transfers that were due while as many requests as allowed were open; never sent.")
    long skipped,
    @Schema(description = "Transfers not sent because the client journal could not store them first.")
    long notJournaled,
    long inFlight,
    @Schema(description = "Since the bank started, whichever generator sent; null until the journal was read, or while it cannot be.", nullable = true)
    @Nullable ClientTotals total
) {
    public LoadStatus withTotal(@Nullable ClientTotals total) {
        return new LoadStatus(
            startedAt, baseTps, targetTps, pulse, requested, acknowledged, posted, rejected, rejectedByReason, failed, unknown, retries, refused,
            duplicatesSent, skipped, notJournaled, inFlight, total);
    }

    @Schema(description = "A rise of the load above its base for a while.")
    public record Pulse(int tps, Instant until) {
    }
}
