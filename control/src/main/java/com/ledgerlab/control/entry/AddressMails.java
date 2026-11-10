package com.ledgerlab.control.entry;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record AddressMails(int links, int limits, @Nullable Instant oldestLink) {
}
