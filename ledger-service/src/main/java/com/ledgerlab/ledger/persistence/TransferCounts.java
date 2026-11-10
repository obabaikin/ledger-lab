package com.ledgerlab.ledger.persistence;

import com.ledgerlab.common.RejectReason;
import java.util.Map;

public record TransferCounts(long posted, long rejected, Map<RejectReason, Long> rejectionsByReason, long poolPostings) {
}
