package com.ledgerlab.control.chaos;

import java.time.Instant;
import java.util.List;

public record ChaosEvent(Instant at, List<String> instances) {
}
