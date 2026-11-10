package com.ledgerlab.control.entry;

import java.util.UUID;

public record Session(UUID sessionId, String token) {
}
