package com.ledgerlab.control.bank;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.DatabaseClock;
import com.ledgerlab.control.availability.Availability;
import com.ledgerlab.control.availability.AvailabilityRepository;
import com.ledgerlab.control.chaos.ChaosRepository;
import com.ledgerlab.control.chaos.ChaosService;
import com.ledgerlab.control.periods.Period;
import com.ledgerlab.control.periods.PeriodNumbers;
import com.ledgerlab.control.periods.PeriodRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankService {

    public static final int DEFAULT_PERIODS = 10;
    public static final int MAX_PERIODS = 100;

    private final PeriodRepository periods;
    private final BankLive live;
    private final Availability availability;
    private final AvailabilityRepository windows;
    private final ChaosService chaos;
    private final ChaosRepository chaosEvents;
    private final DatabaseClock clock;
    private final ControlProperties props;

    public BankService(
        PeriodRepository periods,
        BankLive live,
        Availability availability,
        AvailabilityRepository windows,
        ChaosService chaos,
        ChaosRepository chaosEvents,
        DatabaseClock clock,
        ControlProperties props
    ) {
        this.periods = periods;
        this.live = live;
        this.availability = availability;
        this.windows = windows;
        this.chaos = chaos;
        this.chaosEvents = chaosEvents;
        this.clock = clock;
        this.props = props;
    }

    /** One snapshot: a period reconciled between two of the reads would be counted twice, or not at all. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BankView overview() {
        var now = clock.now();
        var snapshot = live.snapshot();
        var totals = periods.numbersOfFinished();
        for (var unfinished : periods.unfinishedStarts()) {
            var stats = snapshot.stats().get(unfinished);
            if (stats != null) {
                totals = totals.plus(new PeriodNumbers(
                    stats.accepted(), stats.posted(), stats.rejected(), stats.postedAmountCents(), stats.rejectionsByReason()));
            }
        }
        var current = Period.containing(now, props.periods().length());
        var books = snapshot.books();
        var booksReadAt = snapshot.booksReadAt();
        var clients = snapshot.clients();
        return new BankView(
            periods.firstStart(),
            props.periods().length().toMinutes(),
            new BankView.CurrentPeriod(current.start(), current.end(), current.end().plus(props.periods().closeDelay())),
            totals,
            books == null || booksReadAt == null
                ? null
                : new BankView.BooksNow(books.accounts(), books.openingTotalCents(), books.balanceTotalCents(), books.balanced(), booksReadAt),
            clients == null
                ? null
                : new BankView.Clients(
                    clients.startedAt(), clients.baseTps(), clients.targetTps(), clients.pulse(), clients.requested(), clients.acknowledged(),
                    clients.failed() + clients.unknown(), clients.refused(), clients.retries(), clients.inFlight()),
            snapshot.pods(),
            availability.slo(now),
            windows.nextWindow(now),
            new BankView.Chaos(
                props.chaos().enabled(), chaos.readyInSeconds(), chaosEvents.since(now.minus(props.bank().recentChaos()))),
            snapshot.readAt());
    }

    @Transactional(readOnly = true)
    public PeriodList endedPeriods(int limit) {
        if (limit < 1 || limit > MAX_PERIODS) {
            throw new InvalidLimitException();
        }
        return new PeriodList(periods.endedBy(clock.now(), limit).stream().map(PeriodView::of).toList());
    }

    @Transactional(readOnly = true)
    public PeriodDetails period(Instant start) {
        var period = periods.find(start);
        if (period == null || period.end().isAfter(clock.now())) {
            throw new UnknownPeriodException();
        }
        return new PeriodDetails(PeriodView.of(period), periods.reportOf(start));
    }
}
