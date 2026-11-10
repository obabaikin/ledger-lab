package com.ledgerlab.generator;

import java.time.Duration;
import java.util.List;

public record ProfileProperties(
    double duplicateShare,
    // Delay of a duplicate after the first send starts, picked from this list; it may overlap that send.
    List<Duration> duplicateDelays,
    // Share of transfers no account can cover, picked by count so a run has exactly that many.
    double oversizedShare,
    double blockedShare
) {
}
