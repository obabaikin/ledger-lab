package com.ledgerlab.generator.load;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "How the bank answered the clients' requests that ended in one minute.")
public record MinuteResponses(
    @Schema(description = "Start of the minute.")
    Instant minute,
    @Schema(description = "Requests that ended in the minute, retries and duplicates included.")
    long total,
    @Schema(description = "Of them without a usable answer: a 5xx other than busy, no connection, or no answer in time.")
    long errors,
    @Schema(description = "Of them refused as busy (503): an answer, the transfer was not taken.")
    long busy
) {
}
