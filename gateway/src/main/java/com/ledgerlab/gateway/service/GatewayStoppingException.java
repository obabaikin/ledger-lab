package com.ledgerlab.gateway.service;

public class GatewayStoppingException extends RuntimeException {

    public GatewayStoppingException() {
        super("The gateway is stopping");
    }
}
