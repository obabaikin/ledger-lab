package com.ledgerlab.generator;

import java.util.List;
import org.jspecify.annotations.Nullable;

final class Percentiles {

    private Percentiles() {
    }

    /** Linear interpolation between ranks, as PostgreSQL's percentile_cont, so client and bank percentiles compare. */
    static @Nullable Double percentileCont(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return null;
        }
        var rank = p * (sorted.size() - 1);
        var lower = (int) rank;
        var upper = Math.min(lower + 1, sorted.size() - 1);
        return sorted.get(lower) + (rank - lower) * (sorted.get(upper) - sorted.get(lower));
    }
}
