package com.ledgerlab.control.chaos;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.entry.InviteRepository;
import com.ledgerlab.control.entry.SessionNotFoundException;
import com.ledgerlab.control.runs.RunRepository;
import com.ledgerlab.control.runs.RunState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChaosService {

    private static final long MILLIS_PER_SECOND = 1000L;

    private static final Logger log = LoggerFactory.getLogger(ChaosService.class);

    private final InviteRepository invites;
    private final RunRepository runs;
    private final ChaosRepository events;
    private final LedgerInstances ledger;
    private final ControlProperties props;

    public ChaosService(InviteRepository invites, RunRepository runs, ChaosRepository events, LedgerInstances ledger, ControlProperties props) {
        this.invites = invites;
        this.runs = runs;
        this.events = events;
        this.ledger = ledger;
        this.props = props;
    }

    /**
     * The lock is held over the calls to the instances, so a second press waits and sees this one's event.
     * The time is taken after the lock, as in {@link RunRepository}: the waiting transaction's now() would lie before it.
     */
    @Transactional
    public ChaosEvent crash(UUID sessionId) throws InterruptedException, ExecutionException {
        if (invites.findSession(sessionId) == null) {
            throw new SessionNotFoundException();
        }
        if (!props.chaos().enabled()) {
            throw new ChaosNotAvailableException();
        }
        if (!runs.hasRun(sessionId, List.of(RunState.RUNNING))) {
            throw new NoRunningRunException();
        }
        events.lock();
        var at = runs.now();
        var readyIn = secondsUntilReady(events.lastAt(), at);
        if (readyIn > 0) {
            throw new ChaosUsedRecentlyException(readyIn);
        }
        var crashed = ledger.crashAll();
        if (crashed.isEmpty()) {
            throw new LedgerDidNotAnswerException();
        }
        var event = new ChaosEvent(at, crashed);
        events.insert(event);
        log.info("Chaos: crashed {}", crashed);
        return event;
    }

    /** 0 when chaos may be used now; the same number a refused press sends as Retry-After. */
    @Transactional(readOnly = true)
    public long readyInSeconds() {
        return props.chaos().enabled() ? secondsUntilReady(events.lastAt(), runs.now()) : 0;
    }

    private long secondsUntilReady(@Nullable Instant lastAt, Instant now) {
        if (lastAt == null) {
            return 0;
        }
        var left = Duration.between(now, lastAt.plus(props.chaos().minInterval()));
        if (left.compareTo(Duration.ZERO) <= 0) {
            return 0;
        }
        return Math.max(Math.ceilDiv(left.toMillis(), MILLIS_PER_SECOND), 1);
    }
}
