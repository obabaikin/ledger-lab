package com.ledgerlab.generator;

import org.jspecify.annotations.Nullable;

public class JournalUnavailableException extends RuntimeException {

    public JournalUnavailableException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
