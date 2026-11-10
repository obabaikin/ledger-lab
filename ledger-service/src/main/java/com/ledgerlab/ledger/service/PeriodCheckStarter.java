package com.ledgerlab.ledger.service;

import com.ledgerlab.ledger.domain.Period;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The check reads a whole period and takes seconds under load: it runs beside the request that asked for it. */
@Component
public class PeriodCheckStarter {

    private static final Logger log = LoggerFactory.getLogger(PeriodCheckStarter.class);

    private final PeriodChecker checker;
    private final Set<Instant> running = ConcurrentHashMap.newKeySet();

    public PeriodCheckStarter(PeriodChecker checker) {
        this.checker = checker;
    }

    /** Asking again is harmless: a check that is running or kept is not started a second time. */
    public void start(Period period) {
        if (!running.add(period.start())) {
            return;
        }
        Thread.ofVirtual().name("period-check-" + period.start()).start(() -> {
            try {
                if (checker.check(period)) {
                    log.info("Checked the period from {}", period.start());
                }
            } catch (RuntimeException e) {
                log.error("The check of the period from {} failed; the next request starts it again", period.start(), e);
            } finally {
                running.remove(period.start());
            }
        });
    }
}
