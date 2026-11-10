package com.ledgerlab.control.runs;

import org.jspecify.annotations.Nullable;

public record QuotaUsage(long used, @Nullable Long retryAfterSeconds) {
}
