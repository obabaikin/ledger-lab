package com.ledgerlab.control.metrics;

import com.ledgerlab.control.generator.GeneratorApi;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "What the clients counted, from the generator's status.")
public record ClientCounts(
    long requested,
    long acknowledged,
    long failed,
    long unknown,
    long retries,
    @Schema(description = GeneratorApi.REFUSED_DESCRIPTION)
    long refused,
    @Schema(description = "Second presses of the same transfer; the gateway answers them without a new transfer.")
    long duplicatesSent,
    @Schema(description = "New transfers per second from the clients' start; index 0 is the first second.")
    List<Long> requestedPerSecond
) {
}
