package com.ledgerlab.control.availability;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "How soon the bank posts transfers, by minute: a minute is bad if more than a small share of its transfers took longer than the threshold from acceptance to result.")
public record Freshness(
    @Schema(description = "Share of good minutes the bank aims for.")
    BigDecimal target,
    @Schema(description = "Longest time from acceptance to result a transfer may take to count as fast.")
    long thresholdMs,
    @Schema(description = "Bad minutes that use up the budget.")
    long badMinutes,
    @Schema(description = "Bad minutes the target allows in a full window.")
    long budgetMinutes,
    @Schema(description = "Bad minutes within a maintenance window declared in time; they do not use up the budget.")
    long plannedMinutes,
    @Schema(description = "Bad minutes during a crash or a load above the promised one, with their aftermath; they do not use up the budget.")
    long outsideMinutes
) {
}
