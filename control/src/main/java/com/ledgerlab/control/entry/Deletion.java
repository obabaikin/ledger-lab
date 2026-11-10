package com.ledgerlab.control.entry;

import java.time.Instant;

public record Deletion(Instant deleteAt, boolean alreadyAsked) {
}
