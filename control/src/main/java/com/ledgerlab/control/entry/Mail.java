package com.ledgerlab.control.entry;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record Mail(
    long mailId,
    @Nullable String email,
    String token,
    MailKind kind,
    @Nullable Instant windowFreesAt,
    int attempts
) {
}
