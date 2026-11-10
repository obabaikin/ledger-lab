package com.ledgerlab.control.metrics;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "One instance of a service, from Prometheus; approximate, a scrape or two behind.")
public record PodView(
    String job,
    String instance,
    @Schema(description = "The last scrape got an answer.")
    boolean up,
    @Schema(description = "Partitions the ledger instance consumes; null for other services.", nullable = true)
    @Nullable Integer partitions
) {
}
