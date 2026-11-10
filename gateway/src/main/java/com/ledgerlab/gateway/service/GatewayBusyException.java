package com.ledgerlab.gateway.service;

public class GatewayBusyException extends RuntimeException {

    public GatewayBusyException() {
        super("Too many transfers in progress");
    }
}
