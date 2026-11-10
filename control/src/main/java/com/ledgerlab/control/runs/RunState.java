package com.ledgerlab.control.runs;

import java.util.Arrays;
import java.util.List;

public enum RunState {
    QUEUED(false, false),
    CREATED(false, true),
    RUNNING(false, true),
    DRAINING(false, true),
    RECONCILED(true, false),
    FAILED(true, false),
    EXPIRED(true, false);

    public static final List<RunState> UNFINISHED_STATES = Arrays.stream(values()).filter(it -> !it.isFinal).toList();
    public static final List<RunState> SLOT_STATES = Arrays.stream(values()).filter(it -> it.holdsSlot).toList();

    private final boolean isFinal;
    private final boolean holdsSlot;

    RunState(boolean isFinal, boolean holdsSlot) {
        this.isFinal = isFinal;
        this.holdsSlot = holdsSlot;
    }

    public boolean isFinal() {
        return isFinal;
    }

    public boolean holdsSlot() {
        return holdsSlot;
    }
}
