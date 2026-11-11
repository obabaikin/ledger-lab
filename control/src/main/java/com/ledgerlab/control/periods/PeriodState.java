package com.ledgerlab.control.periods;

public enum PeriodState {
    OPEN(false),
    CLOSING(false),
    DRAINING(false),
    RECONCILED(true),
    FAILED(true);

    private final boolean isFinal;

    PeriodState(boolean isFinal) {
        this.isFinal = isFinal;
    }

    public boolean isFinal() {
        return isFinal;
    }
}
