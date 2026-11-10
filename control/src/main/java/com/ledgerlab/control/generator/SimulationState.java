package com.ledgerlab.control.generator;

public enum SimulationState {
    RUNNING(true),
    STOPPING(true),
    FINISHED(false),
    STOPPED(false);

    private final boolean active;

    SimulationState(boolean active) {
        this.active = active;
    }

    public boolean isActive() {
        return active;
    }
}
