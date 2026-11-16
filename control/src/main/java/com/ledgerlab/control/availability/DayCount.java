package com.ledgerlab.control.availability;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "What used the budget in one UTC day.")
public record DayCount(
    LocalDate day,
    @Schema(description = "Bad minutes, or transfers over the threshold, that were judged; the planned and outside left out.")
    long count
) {
}
