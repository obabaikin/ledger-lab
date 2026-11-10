package com.ledgerlab.control.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "A new session; its id is the key to it.")
public record SessionCreated(UUID sessionId) {
}
