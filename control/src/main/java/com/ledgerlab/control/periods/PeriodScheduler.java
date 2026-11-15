package com.ledgerlab.control.periods;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PeriodScheduler {

    private final PeriodConductor conductor;

    public PeriodScheduler(PeriodConductor conductor) {
        this.conductor = conductor;
    }

    @Scheduled(fixedDelayString = "${control.periods.tick-interval}", initialDelayString = "${control.periods.tick-interval}")
    public void tick() {
        conductor.tick();
    }
}
