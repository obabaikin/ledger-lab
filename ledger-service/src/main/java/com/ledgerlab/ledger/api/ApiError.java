package com.ledgerlab.ledger.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Body of an error answer.")
public record ApiError(@Schema(description = "Names the case.") String error) {
}
