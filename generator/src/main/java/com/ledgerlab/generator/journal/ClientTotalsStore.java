package com.ledgerlab.generator.journal;

import com.ledgerlab.generator.GeneratorProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The clients' totals: the folded row plus the increments not folded yet, read in one statement so both are of one snapshot. */
@Component
public class ClientTotalsStore {

    /** Reads missed in a row before the totals are no longer shown as now. */
    static final int STALE_AFTER_READS = 3;

    private static final String READ =
        """
        SELECT t.sent + d.sent AS sent,
               t.acknowledged + d.acknowledged AS acknowledged,
               t.failed + d.failed AS failed,
               t.unknown_accepted + d.unknown_accepted AS unknown_accepted,
               t.unknown_not_accepted + d.unknown_not_accepted AS unknown_not_accepted
        FROM client_totals t,
             (SELECT coalesce(sum(sent), 0) AS sent, coalesce(sum(acknowledged), 0) AS acknowledged, coalesce(sum(failed), 0) AS failed,
                     coalesce(sum(unknown_accepted), 0) AS unknown_accepted, coalesce(sum(unknown_not_accepted), 0) AS unknown_not_accepted
              FROM client_totals_delta) d
        """;
    /** DELETE … RETURNING hands each increment to one fold only, however many generators fold at once. */
    private static final String ROLL_UP =
        """
        WITH d AS (DELETE FROM client_totals_delta RETURNING *)
        UPDATE client_totals t
        SET sent = t.sent + s.sent,
            acknowledged = t.acknowledged + s.acknowledged,
            failed = t.failed + s.failed,
            unknown_accepted = t.unknown_accepted + s.unknown_accepted,
            unknown_not_accepted = t.unknown_not_accepted + s.unknown_not_accepted
        FROM (SELECT coalesce(sum(sent), 0) AS sent, coalesce(sum(acknowledged), 0) AS acknowledged, coalesce(sum(failed), 0) AS failed,
                     coalesce(sum(unknown_accepted), 0) AS unknown_accepted, coalesce(sum(unknown_not_accepted), 0) AS unknown_not_accepted
              FROM d) s
        """;

    private static final Logger log = LoggerFactory.getLogger(ClientTotalsStore.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration staleAfter;
    private final boolean othersSentBefore;
    private volatile @Nullable Read last = null;
    /** Touched by the scheduler's reads only, one at a time. */
    private long failedReadsInARow = 0L;

    private record Read(ClientTotals totals, Instant at) {
    }

    public ClientTotalsStore(JdbcClient jdbc, Clock clock, GeneratorProperties props) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.staleAfter = props.totals().readInterval().multipliedBy(STALE_AFTER_READS);
        // Read before the load starts, which Spring does once every bean is made: this generator's own first
        // transfers must not count as another's.
        this.othersSentBefore = read(jdbc).sent() > 0;
    }

    /** Whether clients sent before this generator started: its own counts then fall short of the totals. */
    public boolean othersSentBefore() {
        return othersSentBefore;
    }

    /** null before the first read, or when the database has not answered for a while. */
    public @Nullable ClientTotals current() {
        var read = last;
        if (read == null || read.at().plus(staleAfter).isBefore(clock.instant())) {
            return null;
        }
        return read.totals();
    }

    @Scheduled(fixedDelayString = "${generator.totals.read-interval}")
    public void refresh() {
        try {
            last = new Read(read(), clock.instant());
            if (failedReadsInARow > 0) {
                log.info("The clients' totals are read again after {} reads that failed", failedReadsInARow);
                failedReadsInARow = 0;
            }
        } catch (DataAccessException e) {
            // While the database is away every read fails the same way: the first is logged.
            if (failedReadsInARow++ == 0) {
                log.warn("The clients' totals could not be read; they are not shown until a read succeeds", e);
            }
        }
    }

    public ClientTotals read() {
        return read(jdbc);
    }

    private static ClientTotals read(JdbcClient jdbc) {
        return jdbc.sql(READ).query((rs, row) -> {
            var sent = rs.getLong("sent");
            var acknowledged = rs.getLong("acknowledged");
            var gaveUp = rs.getLong("failed") + rs.getLong("unknown_accepted") + rs.getLong("unknown_not_accepted");
            return new ClientTotals(sent, acknowledged, gaveUp, sent - acknowledged - gaveUp);
        }).single();
    }

    @Scheduled(fixedDelayString = "${generator.totals.rollup-interval}", initialDelayString = "${generator.totals.rollup-interval}")
    public void rollUpNow() {
        try {
            rollUp();
        } catch (DataAccessException e) {
            log.warn("The clients' increments could not be folded; they stay and are counted until the next fold", e);
        }
    }

    public void rollUp() {
        jdbc.sql(ROLL_UP).update();
    }
}
