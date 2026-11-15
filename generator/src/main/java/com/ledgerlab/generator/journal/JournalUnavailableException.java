package com.ledgerlab.generator.journal;

import org.jspecify.annotations.Nullable;

public class JournalUnavailableException extends RuntimeException {

    public JournalUnavailableException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
