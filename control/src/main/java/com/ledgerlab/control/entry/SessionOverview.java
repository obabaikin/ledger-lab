package com.ledgerlab.control.entry;

import java.util.UUID;

public record SessionOverview(UUID sessionId, boolean inviteValid, boolean chaosAvailable, long chaosReadyInSeconds) {
}
