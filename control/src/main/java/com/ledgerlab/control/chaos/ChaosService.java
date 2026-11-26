package com.ledgerlab.control.chaos;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.DatabaseClock;
import com.ledgerlab.control.entry.InviteNotValidException;
import com.ledgerlab.control.entry.InviteRepository;
import com.ledgerlab.control.entry.SessionNotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    /** Read by the release check (infra/release-check), which leaves a window with chaos in it unchecked. */
    public static final String METRIC = "control.chaos.events";

    private static final Logger log = LoggerFactory.getLogger(ChaosService.class);

    private final InviteRepository invites;
    private final ChaosRepository events;
    private final LedgerInstances ledger;
    private final DatabaseClock clock;
    private final ControlProperties props;
    private final Counter crashes;

    public ChaosService(
        InviteRepository invites,
        ChaosRepository events,
        LedgerInstances ledger,
        DatabaseClock clock,
        ControlProperties props,
        MeterRegistry registry
    ) {
        this.invites = invites;
        this.clock = clock;
        this.events = events;
        this.ledger = ledger;
        this.props = props;
        this.crashes = Counter.builder(METRIC).description("Chaos presses that sent a crash to the ledger").register(registry);
    }

    /**
     * The lock is held over the calls to the instances; a press meanwhile is refused at once rather than holding a pooled
     * connection while it waits. It is told to ask again once those calls are over: this press's outcome is not known yet.
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
        if (!events.tryLock()) {
            throw new ChaosUsedRecentlyException(wholeSeconds(props.chaos().requestTimeout()));
        }
        var at = clock.now();
        var readyIn = secondsUntilReady(events.lastAt(), at);
        if (readyIn > 0) {
            throw new ChaosUsedRecentlyException(readyIn);
        }
        // Counted before the calls: an instance may crash even when its answer is lost.
        crashes.increment();
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
        return left.compareTo(Duration.ZERO) <= 0 ? 0 : wholeSeconds(left);
    }

    private static long wholeSeconds(Duration duration) {
        return Math.max(Math.ceilDiv(duration.toMillis(), MILLIS_PER_SECOND), 1);
    }
}
