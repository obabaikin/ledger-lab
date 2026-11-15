package com.ledgerlab.reporting.service;

import com.ledgerlab.reporting.ReportingProperties;
import com.ledgerlab.reporting.domain.Period;
import com.ledgerlab.reporting.domain.PeriodStats;
import com.ledgerlab.reporting.persistence.SecondStatsRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class PeriodStatsService {

    private final SecondStatsRepository seconds;
    private final ReportingProperties.Periods props;
    private final Clock clock;

    public PeriodStatsService(SecondStatsRepository seconds, ReportingProperties props, Clock clock) {
        this.seconds = seconds;
        this.props = props.periods();
        this.clock = clock;
    }

    public PeriodStats current() {
        return seconds.sum(Period.containing(clock.instant(), props.length()));
    }

    /** Of a period that has begun and whose numbers are still held. */
    public PeriodStats of(Instant periodStart) {
        var length = props.length();
        if (!Period.startsOnGrid(periodStart, length)) {
            throw new UnknownPeriodException(periodStart, "periods are " + length + " long from the epoch");
        }
        var now = clock.instant();
        var period = new Period(periodStart, periodStart.plus(length));
        if (periodStart.isAfter(now)) {
            throw new UnknownPeriodException(periodStart, "it has not begun");
        }
        if (!period.end().isAfter(now.minus(props.retention()))) {
            throw new UnknownPeriodException(periodStart, "it is older than the retention of " + props.retention());
        }
        return seconds.sum(period);
    }
}
