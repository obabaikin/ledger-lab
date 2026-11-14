package com.ledgerlab.ledger.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Whether the period's check is made, read without counting the period's transfers. */
public record PeriodCheckState(
    @Schema(description = "Absent until the check asked for by POST has finished") @Nullable PeriodCheck check
) {
}
