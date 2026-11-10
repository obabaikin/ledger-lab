package com.ledgerlab.control.generator;

import java.util.UUID;

public record StartSimulationRequest(
    UUID runId,
    int tps,
    int durationSeconds,
    int accountCount,
    int blockedAccounts
) {
}
