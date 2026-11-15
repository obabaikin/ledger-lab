package com.ledgerlab.control.generator;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Transfers the clients did not know the outcome of, settled by asking the bank.")
public record UnknownResolved(long accepted, long notAccepted) {
}
