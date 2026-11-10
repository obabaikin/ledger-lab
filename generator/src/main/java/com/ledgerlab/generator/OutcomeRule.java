package com.ledgerlab.generator;

import java.util.List;

final class OutcomeRule {

    private OutcomeRule() {
    }

    /**
     * The outcome of a transfer from all its sends, given in the order they ended (client contract,
     * ADR 0006): any 2xx means the gateway has it; otherwise a send that may have arrived leaves it
     * unknown; only then has it failed. A final status outranks an accepted one, a later answer an earlier one.
     */
    static SubmissionResult combine(List<SubmissionResult> results) {
        var acknowledged = results.stream().filter(it -> it.outcome() == SubmissionOutcome.ACKNOWLEDGED).toList();
        if (!acknowledged.isEmpty()) {
            return acknowledged.reversed().stream()
                .filter(it -> it.status() != null && it.status().isFinal())
                .findFirst()
                .orElse(acknowledged.getLast());
        }
        return results.stream().filter(it -> it.outcome() == SubmissionOutcome.UNKNOWN).findFirst().orElse(results.getLast());
    }
}
