package com.ledgerlab.control.load;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.DatabaseClock;
import com.ledgerlab.control.entry.InviteNotValidException;
import com.ledgerlab.control.entry.InviteRepository;
import com.ledgerlab.control.entry.SessionNotFoundException;
import com.ledgerlab.control.generator.GeneratorClient;
import com.ledgerlab.control.generator.Pulse;
import com.ledgerlab.control.generator.PulseRequest;
import com.ledgerlab.control.http.Reply;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoadService {

    private static final long MILLIS_PER_SECOND = 1000L;

    private static final Logger log = LoggerFactory.getLogger(LoadService.class);

    private final InviteRepository invites;
    private final LoadRepository pulses;
    private final GeneratorClient generator;
    private final DatabaseClock clock;
    private final ControlProperties props;

    public LoadService(InviteRepository invites, LoadRepository pulses, GeneratorClient generator, DatabaseClock clock, ControlProperties props) {
        this.invites = invites;
        this.pulses = pulses;
        this.generator = generator;
        this.clock = clock;
        this.props = props;
    }

    /**
     * The lock is held over the call to the generator, so a second press waits and sees this one's pulse; the time is taken
     * after the lock. A pulse the generator may have taken without saying so is kept: the next press waits for it as for any.
     */
    @Transactional(noRollbackFor = GeneratorDidNotAnswerException.class)
    public Pulse raise(UUID sessionId, @Nullable Integer tps, @Nullable Integer seconds) throws InterruptedException {
        var load = props.load();
        if (tps == null || seconds == null || !load.tpsOptions().contains(tps) || !load.secondsOptions().contains(seconds)) {
            throw new InvalidLoadException();
        }
        var session = invites.findSession(sessionId);
        if (session == null) {
            throw new SessionNotFoundException();
        }
        var invite = invites.find(session.token());
        if (invite == null || !invite.valid()) {
            throw new InviteNotValidException();
        }
        pulses.lock();
        var at = clock.now();
        var lastUntil = pulses.lastUntil();
        if (lastUntil != null) {
            var readyIn = secondsUntilReady(lastUntil, at);
            if (readyIn > 0) {
                throw at.isBefore(lastUntil) ? new LoadRunningException(readyIn) : new LoadUsedRecentlyException(readyIn);
            }
        }
        var pulse = new LoadPulse(at, tps, seconds, at.plusSeconds(seconds));
        switch (generator.pulse(new PulseRequest(tps, seconds))) {
            case Reply.Answered<?> answered -> pulses.insert(pulse);
            case Reply.Refused<?> refused -> {
                log.warn("Load: the generator refused a pulse of {} TPS for {} s: {} {}", tps, seconds, refused.status(), refused.error());
                throw new GeneratorDidNotAnswerException();
            }
            case Reply.NotConnected<?> notConnected -> {
                log.warn("Load: the generator could not be reached for a pulse of {} TPS for {} s", tps, seconds);
                throw new GeneratorDidNotAnswerException();
            }
            case Reply.NoAnswer<?> noAnswer -> throw keptUnconfirmed(pulse);
            case Reply.Unreadable<?> unreadable -> throw keptUnconfirmed(pulse);
        }
        log.info("Load: pulse of {} TPS for {} s", tps, seconds);
        return new Pulse(tps, pulse.until());
    }

    /** 0 when a pulse may be started now; the same number a refused press sends as Retry-After. */
    @Transactional(readOnly = true)
    public long readyInSeconds() {
        var lastUntil = pulses.lastUntil();
        return lastUntil == null ? 0 : secondsUntilReady(lastUntil, clock.now());
    }

    private GeneratorDidNotAnswerException keptUnconfirmed(LoadPulse pulse) {
        pulses.insert(pulse);
        log.warn("Load: the generator gave no readable answer to a pulse of {} TPS for {} s; it is taken as started", pulse.tps(), pulse.seconds());
        return new GeneratorDidNotAnswerException();
    }

    private long secondsUntilReady(Instant lastUntil, Instant now) {
        var left = Duration.between(now, lastUntil.plus(props.load().minInterval()));
        if (left.compareTo(Duration.ZERO) <= 0) {
            return 0;
        }
        return Math.max(Math.ceilDiv(left.toMillis(), MILLIS_PER_SECOND), 1);
    }
}
