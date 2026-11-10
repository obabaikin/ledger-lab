package com.ledgerlab.gateway.service;

import java.time.Duration;

/** The submission may still be written after this: to the client it is unknown, as any other 5xx. */
public class AcceptTimeoutException extends RuntimeException {

    public AcceptTimeoutException(Duration timeout) {
        super("No answer from the writers within " + timeout);
    }
}
