package com.ledgerlab.control.runs;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What the gateway counted for the run.")
public record GatewayCounts(long accepted, long published, long posted, long rejected) {
}
