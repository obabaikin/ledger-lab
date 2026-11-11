package com.ledgerlab.control.bank;

import com.ledgerlab.control.metrics.BankMetrics;

public enum SeriesName {
    ACCEPTED_PER_SECOND(BankMetrics.ACCEPTED_RATE),
    POSTED_PER_SECOND(BankMetrics.POSTED_RATE),
    REJECTED_PER_SECOND(BankMetrics.REJECTED_RATE),
    BUSY_PER_SECOND(BankMetrics.BUSY_RATE),
    BANK_LATENCY_P50_MS(BankMetrics.LATENCY_P50_MS),
    BANK_LATENCY_P99_MS(BankMetrics.LATENCY_P99_MS);

    private final String query;

    SeriesName(String query) {
        this.query = query;
    }

    public String query() {
        return query;
    }
}
