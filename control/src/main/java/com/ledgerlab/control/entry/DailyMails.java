package com.ledgerlab.control.entry;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record DailyMails(int count, @Nullable Instant oldest) {
}
