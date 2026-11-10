package com.ledgerlab.control.runs;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ConductorScheduler {

    private final Conductor conductor;

    public ConductorScheduler(Conductor conductor) {
        this.conductor = conductor;
    }

    @Scheduled(fixedDelayString = "${control.queue.poll-interval}", initialDelayString = "${control.queue.poll-interval}")
    public void tick() {
        conductor.tick();
    }
}
