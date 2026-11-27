package com.ledgerlab.generator.load;

import com.ledgerlab.generator.GeneratorProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Lets one instance of the generator send at a time, so two pods during a deploy do not double the load. The lease is a
 * row in Postgres; whether this instance may send is decided in memory, by {@link #granted()}, without the database.
 */
@Component
public final class SenderLease implements SendPermission, SmartLifecycle {

    public static final String HELD_METRIC = "generator.sender.lease.held";

    private static final String TAKE_OR_RENEW =
        """
        INSERT INTO sender_lease (only_row, owner, expires_at) VALUES (true, :owner, now() + CAST(:millis AS bigint) * interval '1 millisecond')
        ON CONFLICT (only_row) DO UPDATE SET owner = EXCLUDED.owner, expires_at = EXCLUDED.expires_at
        WHERE sender_lease.owner = EXCLUDED.owner OR sender_lease.expires_at < now()
        RETURNING owner
        """;
    private static final String RELEASE = "DELETE FROM sender_lease WHERE owner = :owner";

    private static final Logger log = LoggerFactory.getLogger(SenderLease.class);

    private final JdbcClient jdbc;
    private final GeneratorProperties.Lease props;
    private final boolean enabled;

    /**
     * Held over a statement on the lease, so a release cannot be overtaken by a renewal still on its way. The renewer is
     * not interrupted: a virtual thread interrupted in a socket read closes the connection under the pool.
     */
    private final Object statements = new Object();
    /** Guards the pair below: a renewal that answers after a stop must not grant sending again. */
    private final Object state = new Object();
    private boolean mayRenew = false;
    private volatile long sendUntilNanos = 0L;
    private volatile boolean held = false;
    /** Touched under {@link #statements} only. */
    private boolean failing = false;

    public SenderLease(JdbcClient jdbc, GeneratorProperties props, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.props = props.lease();
        this.enabled = props.load().enabled();
        Gauge.builder(HELD_METRIC, this, lease -> lease.held ? 1 : 0)
            .description("1 while this instance holds the lease to send, 0 otherwise")
            .register(registry);
    }

    @Override
    public boolean granted() {
        return System.nanoTime() - sendUntilNanos < 0;
    }

    @Override
    public void start() {
        if (!enabled) {
            return;
        }
        synchronized (state) {
            if (mayRenew) {
                return;
            }
            mayRenew = true;
        }
        Thread.ofVirtual().name("sender-lease").start(this::keepRenewing);
    }

    private void keepRenewing() {
        try {
            while (isRunning()) {
                takeOrRenew();
                Thread.sleep(held ? props.renewEvery() : props.acquireEvery());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void takeOrRenew() {
        synchronized (statements) {
            if (!isRunning()) {
                return;
            }
            var sentNanos = System.nanoTime();
            boolean got;
            try {
                got = !jdbc.sql(TAKE_OR_RENEW)
                    .param("owner", props.owner())
                    .param("millis", props.duration().toMillis())
                    .query(String.class)
                    .list()
                    .isEmpty();
            } catch (DataAccessException e) {
                if (!failing) {
                    failing = true;
                    log.warn("The sender lease could not be renewed; sending stops {} after the last renewal", props.sendWindow(), e);
                }
                return;
            }
            if (failing) {
                failing = false;
                log.info("The sender lease answers again");
            }
            synchronized (state) {
                if (!mayRenew) {
                    return;
                }
                if (got) {
                    sendUntilNanos = sentNanos + props.sendWindow().toNanos();
                } else {
                    sendUntilNanos = 0L;
                }
                if (got != held) {
                    held = got;
                    log.info(got ? "This instance took the sender lease: it sends" : "Another instance holds the sender lease: this one is silent");
                }
            }
        }
    }

    /** Stops sending at once and frees the lease, so a new pod takes over without waiting for it to expire. */
    @Override
    public void stop() {
        synchronized (state) {
            if (!mayRenew) {
                return;
            }
            mayRenew = false;
            sendUntilNanos = 0L;
        }
        synchronized (statements) {
            try {
                jdbc.sql(RELEASE).param("owner", props.owner()).update();
                log.info("The sender lease is released");
            } catch (DataAccessException e) {
                log.warn("The sender lease could not be released; it expires within {}", props.duration(), e);
            }
            held = false;
        }
    }

    @Override
    public boolean isRunning() {
        synchronized (state) {
            return mayRenew;
        }
    }

    /** Started after the load, which stays silent until the lease is taken, and stopped before it. */
    @Override
    public int getPhase() {
        return Load.PHASE + 1;
    }
}
