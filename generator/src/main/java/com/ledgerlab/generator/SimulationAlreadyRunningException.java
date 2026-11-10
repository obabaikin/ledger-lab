package com.ledgerlab.generator;

import java.util.UUID;

public class SimulationAlreadyRunningException extends ConflictException {

    public SimulationAlreadyRunningException(UUID runId) {
        super("Simulation " + runId + " is already running");
    }
}
