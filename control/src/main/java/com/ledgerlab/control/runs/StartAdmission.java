package com.ledgerlab.control.runs;

import com.ledgerlab.control.ControlProperties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Bounds the starts that hold a database connection while they wait for the queue's lock, so a burst of starts
 * cannot take the whole pool from the reads of the page and from the conductor's tick.
 */
@Component
public class StartAdmission {

    private final Semaphore permits;
    private final long waitNanos;

    public StartAdmission(ControlProperties props) {
        this.permits = new Semaphore(props.runs().maxConcurrentStarts());
        this.waitNanos = props.runs().startWait().toNanos();
    }

    public <T> T admit(Supplier<T> block) throws InterruptedException {
        if (!permits.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) {
            throw new StartsBusyException();
        }
        try {
            return block.get();
        } finally {
            permits.release();
        }
    }
}
