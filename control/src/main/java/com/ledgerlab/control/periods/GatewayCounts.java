package com.ledgerlab.control.periods;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The period's transfers as the gateway counts them.")
public record GatewayCounts(
    long accepted,
    long published,
    long posted,
    long rejected,
    @Schema(description = "Transfers in the period when the gateway closed it.")
    long entryCount
) {
}
