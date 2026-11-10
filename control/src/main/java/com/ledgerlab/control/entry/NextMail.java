package com.ledgerlab.control.entry;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record NextMail(MailKind kind, @Nullable Instant windowFreesAt) {
}
