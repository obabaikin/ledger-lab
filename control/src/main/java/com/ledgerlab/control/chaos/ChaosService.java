package com.ledgerlab.control.chaos;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.DatabaseClock;
import com.ledgerlab.control.entry.InviteNotValidException;
import com.ledgerlab.control.entry.InviteRepository;
import com.ledgerlab.control.entry.SessionNotFoundException;
import java.time.Duration;
import java.time.Instant;
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
    private final ChaosRepository events;
    private final LedgerInstances ledger;
    private final DatabaseClock clock;
    private final ControlProperties props;

    public ChaosService(InviteRepository invites, ChaosRepository events, LedgerInstances ledger, DatabaseClock clock, ControlProperties props) {
        this.invites = invites;
        this.clock = clock;
        this.events = events;
        this.ledger = ledger;
        this.props = props;
    }

    /**
     * The lock is held over the calls to the instances, so a second press waits and sees this one's event.
     * The time is taken after the lock: the waiting transaction's now() would lie before it.
     */
    @Transactional
    public ChaosEvent crash(UUID sessionId) throws InterruptedException, ExecutionException {
        var session = invites.findSession(sessionId);
        if (session == null) {
            throw new SessionNotFoundException();
        }
        if (!props.chaos().enabled()) {
            throw new ChaosNotAvailableException();
        }
        var invite = invites.find(session.token());
        if (invite == null || !invite.valid()) {
            throw new InviteNotValidException();
        }
        events.lock();
        var at = clock.now();
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
        return props.chaos().enabled() ? secondsUntilReady(events.lastAt(), clock.now()) : 0;
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
