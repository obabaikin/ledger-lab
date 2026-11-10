package com.ledgerlab.ledger.api;

import com.ledgerlab.ledger.service.AccountSetup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

public record OpenAccountsRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @Min(AccountSetup.MIN_ACCOUNTS) @Max(AccountSetup.MAX_ACCOUNTS) int accountCount,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @PositiveOrZero long openingBalanceCents,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @PositiveOrZero int blockedAccounts,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @PositiveOrZero long dailyLimitCents
) {
}
