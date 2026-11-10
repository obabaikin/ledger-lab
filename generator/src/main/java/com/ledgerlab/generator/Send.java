package com.ledgerlab.generator;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Offsets are on the generator's monotonic clock, counted from the start of the run. */
public record Send(long offsetMs, SendResult result, @Schema(nullable = true) @Nullable Integer httpStatus, long latencyMs) {
}
