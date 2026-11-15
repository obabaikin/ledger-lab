package com.ledgerlab.gateway.api;

public class InvalidStatementQueryException extends RuntimeException {

    private final String code;

    public InvalidStatementQueryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
