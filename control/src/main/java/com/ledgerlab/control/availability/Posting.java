package com.ledgerlab.control.availability;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(description = "How soon the bank posts transfers, from acceptance to result, over the last days: the share of transfers within each threshold.")
public record Posting(
    int windowDays,
    @Schema(description = "The first hour counted, when later than the window's start; null when the counts cover the whole window or none are in yet.", nullable = true)
    @Nullable Instant since,
    @Schema(description = "Transfers judged: all counted, but those of minutes left out.")
    long judged,
    @Schema(description = "Transfers left out: their minute lay in a maintenance window declared in time.")
    long planned,
    @Schema(description = "Transfers left out: their minute met a crash or a load above the promised one, with its aftermath.")
    long outside,
    @Schema(description = "One per threshold, the shortest first.")
    List<Target> targets
) {
    public Posting {
        targets = List.copyOf(targets);
    }

    public record Target(
        long thresholdMs,
        @Schema(description = "Share of the judged transfers that should be within the threshold.")
        BigDecimal target,
        @Schema(description = "Judged transfers within the threshold.")
        long within,
        @Schema(description = "within / judged; null before the first judged transfer.", nullable = true)
        @Nullable Double share,
        @Schema(description = "Judged transfers over the threshold, as a share of those the target allows; above 1 the target is missed. Null before the first judged transfer.", nullable = true)
        @Nullable Double budgetUsed
    ) {
    }
}
