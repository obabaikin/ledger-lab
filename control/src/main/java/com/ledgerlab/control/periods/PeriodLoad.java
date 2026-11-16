package com.ledgerlab.control.periods;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "The clients' load in the period: the range of the base by minute, and the highest pulse that met the period.")
public record PeriodLoad(
    @Schema(description = "Lowest base of the period's minutes; null if no minute told it.", nullable = true)
    @Nullable Integer minBaseTps,
    @Schema(description = "Highest base of the period's minutes; null if no minute told it.", nullable = true)
    @Nullable Integer maxBaseTps,
    @Schema(description = "Highest pulse in effect at some time in the period; null without one.", nullable = true)
    @Nullable Integer maxPulseTps
) {
}
