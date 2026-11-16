package com.ledgerlab.control.availability;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(description = "The bank's availability to its clients over the last days, by minute: a minute is good if nearly every request got an answer; busy (503) is an answer.")
public record Slo(
    @Schema(description = "Share of good minutes the bank aims for.")
    BigDecimal target,
    int windowDays,
    @Schema(description = "Minutes with requests in the window; a minute without any is not measured.")
    long measuredMinutes,
    @Schema(description = "Bad minutes that use up the budget.")
    long badMinutes,
    @Schema(description = "Bad minutes the target allows in a full window.")
    long budgetMinutes,
    @Schema(description = "Bad minutes within a maintenance window declared in time; they do not use up the budget.")
    long plannedMinutes,
    @Schema(description = "Bad minutes during a crash or a load above the promised one, with their aftermath; they do not use up the budget.")
    long outsideMinutes,
    @Schema(description = "Share of good minutes among the measured ones, the planned and outside left out; null before the first measured minute.", nullable = true)
    @Nullable Double availability,
    @Schema(description = "Share of requests refused as busy; null before the first request.", nullable = true)
    @Nullable Double busyShare,
    @Schema(description = "Judged bad minutes by UTC day, the last 30 days at most, oldest first; a day without a measured minute is left out.")
    List<DayCount> days
) {
    public Slo {
        days = List.copyOf(days);
    }
}
