package com.ledgerlab.control.load;

import com.ledgerlab.control.ControlProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tells monitoring whether the bank is under a pulse outside the bounds it promises, by the speed and the aftermath the
 * availability is judged by. Read from the pulses kept, not from the press: a control that restarted meanwhile still
 * tells it, and a scrape never waits for the database.
 */
@Component
public class LoadAbovePromise {

    /** Read by the release check (infra/helm/ledger-lab/files/release_check.py), which leaves a window with it at 1 unchecked. */
    public static final String METRIC = "control.load.above.promise";

    private static final int ABOVE = 1;
    private static final int WITHIN = 0;

    private final LoadRepository pulses;
    private final ControlProperties.Outside outside;
    private final AtomicInteger state = new AtomicInteger(WITHIN);

    public LoadAbovePromise(LoadRepository pulses, ControlProperties props, MeterRegistry registry) {
        this.pulses = pulses;
        this.outside = props.outside();
        Gauge.builder(METRIC, state, AtomicInteger::get)
            .description("1 from the start of a pulse above the promised speed to the end of its aftermath, else 0")
            .register(registry);
    }

    @Scheduled(fixedDelayString = "${control.outside.load-poll-interval}")
    public void read() {
        state.set(pulses.aboveNow(outside.guaranteedTps(), outside.loadAftermath()) ? ABOVE : WITHIN);
    }
}
