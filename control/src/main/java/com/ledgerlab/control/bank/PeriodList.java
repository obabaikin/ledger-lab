package com.ledgerlab.control.bank;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record PeriodList(
    @Schema(description = "The periods that are over, newest first.")
    List<PeriodView> periods
) {
}
