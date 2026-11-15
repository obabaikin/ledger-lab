package com.ledgerlab.control;

import com.ledgerlab.control.availability.Availability;
import com.ledgerlab.control.chaos.ChaosRepository;
import com.ledgerlab.control.load.LoadRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The periods' reports and the hours' sums are kept for good; what they were made from is not. */
@Component
public class Cleanup {

    private final ChaosRepository chaos;
    private final LoadRepository load;
    private final Availability availability;
    private final DatabaseClock clock;
    private final ControlProperties props;

    public Cleanup(ChaosRepository chaos, LoadRepository load, Availability availability, DatabaseClock clock, ControlProperties props) {
        this.chaos = chaos;
        this.load = load;
        this.availability = availability;
        this.clock = clock;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${control.cleanup.interval}", initialDelayString = "${control.cleanup.interval}")
    public void purge() {
        chaos.deleteOlderThan(props.cleanup().retention());
        load.deleteOlderThan(props.cleanup().retention());
        availability.deleteOldMinutes(clock.now());
    }
}
