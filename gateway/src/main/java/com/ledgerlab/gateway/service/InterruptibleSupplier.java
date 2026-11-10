package com.ledgerlab.gateway.service;

@FunctionalInterface
public interface InterruptibleSupplier<T> {
    T get() throws InterruptedException;
}
