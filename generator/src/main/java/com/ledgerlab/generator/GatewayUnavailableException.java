package com.ledgerlab.generator;

import org.jspecify.annotations.Nullable;

public class GatewayUnavailableException extends RuntimeException {

    public GatewayUnavailableException(String message) {
        super(message);
    }

    public GatewayUnavailableException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
