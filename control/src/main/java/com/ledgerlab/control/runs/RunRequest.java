package com.ledgerlab.control.runs;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record RunRequest(@Nullable UUID requestId, @Nullable Integer tps, @Nullable Integer durationSeconds) {
}
