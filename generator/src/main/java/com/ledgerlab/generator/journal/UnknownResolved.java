package com.ledgerlab.generator.journal;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Transfers sent in the period before whose outcome the client did not learn, by what the bank said when asked.")
public record UnknownResolved(long accepted, long notAccepted) {
}
