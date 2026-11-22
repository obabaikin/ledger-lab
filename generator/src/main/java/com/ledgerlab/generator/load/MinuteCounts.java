package com.ledgerlab.generator.load;

import java.time.Instant;

public record MinuteCounts(Instant minute, long total, long errors, long busy) {
}
