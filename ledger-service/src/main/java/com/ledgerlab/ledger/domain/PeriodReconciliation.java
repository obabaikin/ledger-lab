package com.ledgerlab.ledger.domain;

import com.ledgerlab.common.RejectReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The ledger's view of one period: clients' transfers by outcome, counted on request, and the kept check. */
public record PeriodReconciliation(
    Instant periodStart,
    long postedTransfers,
    long rejectedTransfers,
    Map<RejectReason, Long> rejectionsByReason,
    @Schema(description = "Postings of cash pooling; not among the transfers above") long poolPostings,
    @Schema(description = "Absent until the check asked for by POST has finished") @Nullable PeriodCheck check
) {
}
