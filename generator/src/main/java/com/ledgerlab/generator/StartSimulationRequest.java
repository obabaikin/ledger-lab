package com.ledgerlab.generator;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record StartSimulationRequest(
    @Schema(nullable = true)
    @NotNull UUID runId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @Min(1) @Max(MAX_TPS) int tps,
    @Schema(description = "At most the generator's configured maximum duration.", requiredMode = Schema.RequiredMode.REQUIRED)
    @Min(1) int durationSeconds,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @Min(MIN_ACCOUNTS) int accountCount,
    @Min(0) int blockedAccounts,
    @Min(0) int hotAccounts,
    @DecimalMin("0.0") @DecimalMax("1.0") double waitShare,
    @Min(1) @Max(MAX_WAIT_TIMEOUT_SECONDS) int waitTimeoutSeconds
) {
    /** Run limits of the public demo (shared vCPUs, a person waiting on the page); raise only with a recorded reason (ADR 0019). */
    private static final long MAX_TPS = 2_000L;
    private static final long MAX_WAIT_TIMEOUT_SECONDS = 30L;
    /** A transfer needs a sender and a different receiver. */
    private static final long MIN_ACCOUNTS = 2L;

    private static final int DEFAULT_BLOCKED_ACCOUNTS = 0;
    private static final int DEFAULT_HOT_ACCOUNTS = 5;
    private static final double DEFAULT_WAIT_SHARE = 0.1;
    private static final int DEFAULT_WAIT_TIMEOUT_SECONDS = 5;

    @JsonCreator
    static StartSimulationRequest withDefaults(
        UUID runId,
        int tps,
        int durationSeconds,
        int accountCount,
        @Nullable Integer blockedAccounts,
        @Nullable Integer hotAccounts,
        @Nullable Double waitShare,
        @Nullable Integer waitTimeoutSeconds
    ) {
        return new StartSimulationRequest(
            runId, tps, durationSeconds, accountCount,
            blockedAccounts != null ? blockedAccounts : DEFAULT_BLOCKED_ACCOUNTS,
            hotAccounts != null ? hotAccounts : DEFAULT_HOT_ACCOUNTS,
            waitShare != null ? waitShare : DEFAULT_WAIT_SHARE,
            waitTimeoutSeconds != null ? waitTimeoutSeconds : DEFAULT_WAIT_TIMEOUT_SECONDS);
    }
}
