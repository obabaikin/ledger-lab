package com.ledgerlab.control.bank;

import com.ledgerlab.control.periods.BankPeriod;
import com.ledgerlab.control.periods.PeriodFailure;
import com.ledgerlab.control.periods.PeriodNumbers;
import com.ledgerlab.control.periods.PeriodState;
import com.ledgerlab.control.periods.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

@Schema(description = "A period of the bank as control sees it now.")
public record PeriodView(
    Instant start,
    Instant end,
    PeriodState state,
    @Schema(description = "When the gateway closed the period, by the bank's clock; null before.", nullable = true)
    @Nullable Instant closedAt,
    @Schema(description = "When control first saw every accepted transfer applied; null before.", nullable = true)
    @Nullable Instant booksFinalAt,
    @Schema(description = "Only once reconciled. Only BALANCED is green.", nullable = true)
    @Nullable Verdict verdict,
    @Schema(description = "Why the period ended without a verdict; only when failed.", nullable = true)
    @Nullable PeriodFailure failure,
    @Schema(description = "When the period reached a final state; null before.", nullable = true)
    @Nullable Instant finishedAt,
    @Schema(description = "null before the period is final, and for a failed one whose numbers could not be read.", nullable = true)
    @Nullable PeriodNumbers numbers
) {

    static PeriodView of(BankPeriod period) {
        return new PeriodView(
            period.start(), period.end(), period.state(), period.closedAt(), period.booksFinalAt(), period.verdict(), period.failure(),
            period.finishedAt(), period.numbers());
    }
}
