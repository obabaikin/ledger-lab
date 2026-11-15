package com.ledgerlab.control.bank;

import com.ledgerlab.control.periods.PeriodReport;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

public record PeriodDetails(
    PeriodView period,
    @Schema(description = "Only once reconciled.", nullable = true)
    @Nullable PeriodReport report
) {
}
