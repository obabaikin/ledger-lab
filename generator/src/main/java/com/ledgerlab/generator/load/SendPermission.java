package com.ledgerlab.generator.load;

/** Whether this instance may send new transfers now; asked for every transfer, so it must not block. */
@FunctionalInterface
public interface SendPermission {

    SendPermission ALWAYS = () -> true;

    boolean granted();
}
