package com.ledgerlab.generator;

public enum SimulationState {
    RUNNING(true),
    /** No new submissions or retries; open requests get {@link Simulation#stopGrace()} to finish. */
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
