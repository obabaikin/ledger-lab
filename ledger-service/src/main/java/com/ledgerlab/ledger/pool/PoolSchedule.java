package com.ledgerlab.ledger.pool;

import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PoolSchedule {

    private final PoolService pool;
    private final Clock clock;

    public PoolSchedule(PoolService pool, Clock clock) {
        this.pool = pool;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ledger.pool.tick-interval}", initialDelayString = "${ledger.pool.tick-interval}")
    public void tick() {
        pool.advance(clock.instant());
    }
}
