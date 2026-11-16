package com.ledgerlab.control.availability;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.DatabaseClock;
import com.ledgerlab.control.generator.GeneratorClient;
import com.ledgerlab.control.generator.MinutesPage;
import com.ledgerlab.control.http.Reply;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** The bank's availability as its clients met it: the generator counts the answers by minute, control keeps and judges them. */
@Service
public class Availability {

    private static final Logger log = LoggerFactory.getLogger(Availability.class);

    private final AvailabilityRepository minutes;
    private final GeneratorClient generator;
    private final PostingCollector posting;
    private final DatabaseClock clock;
    private final ControlProperties.Availability props;
    private final ControlProperties.Posting postingProps;
    private final AvailabilityRepository.Rules rules;
    private volatile boolean lastAnswered = true;

    public Availability(
        AvailabilityRepository minutes,
        GeneratorClient generator,
        PostingCollector posting,
        DatabaseClock clock,
        ControlProperties props
    ) {
        this.minutes = minutes;
        this.generator = generator;
        this.posting = posting;
        this.clock = clock;
        this.props = props.availability();
        this.postingProps = props.posting();
        var outside = props.outside();
        this.rules = new AvailabilityRepository.Rules(
            this.props.goodMinuteAnsweredPercent(), props.maintenance().notice(), outside.guaranteedTps(),
            outside.chaosAftermath(), outside.loadAftermath(), outside.restartMargin());
    }

    @Scheduled(fixedDelayString = "${control.availability.poll-interval}", initialDelayString = "${control.availability.poll-interval}")
    public void collect() throws InterruptedException {
        var reply = generator.minutesAfter(minutes.lastMinute());
        var answered = reply instanceof Reply.Answered<MinutesPage>;
        if (reply instanceof Reply.Answered<MinutesPage>(var code, var page)) {
            minutes.insert(Objects.requireNonNull(page, "the minutes of an answered call").minutes());
        }
        if (answered != lastAnswered) {
            log.warn("Minutes of the clients' answers read: {}; the generator hands over those it kept once it answers again", answered);
        }
        lastAnswered = answered;
        posting.collect(clock.now());
        minutes.rollUpHours(clock.now(), props.minutesRetention(), rules);
    }

    public Slo slo(Instant now) {
        return slo(counts(now));
    }

    /** Both objectives from one read of the minutes, so that they cover the same ones. */
    public Objectives objectives(Instant now) {
        var counts = counts(now);
        return new Objectives(slo(counts), posting(counts, now));
    }

    public record Objectives(Slo slo, Posting posting) {
    }

    private AvailabilityRepository.Counts counts(Instant now) {
        return minutes.countsSince(windowStart(now), rules);
    }

    private Instant windowStart(Instant now) {
        return now.minus(props.sloWindow()).truncatedTo(ChronoUnit.HOURS);
    }

    private Slo slo(AvailabilityRepository.Counts counts) {
        var judged = counts.measuredMinutes() - counts.plannedMinutes() - counts.outsideMinutes();
        return new Slo(
            props.sloTarget(),
            (int) props.sloWindow().toDays(),
            counts.measuredMinutes(),
            counts.badMinutes(),
            budget(props.sloTarget()),
            counts.plannedMinutes(),
            counts.outsideMinutes(),
            judged == 0 ? null : (double) (judged - counts.badMinutes()) / judged,
            counts.total() == 0 ? null : (double) counts.busy() / counts.total());
    }

    private Posting posting(AvailabilityRepository.Counts counts, Instant now) {
        var since = counts.postingSince();
        var judged = counts.postedJudged();
        return new Posting(
            (int) props.sloWindow().toDays(),
            since != null && since.isAfter(windowStart(now)) ? since : null,
            judged,
            counts.postedPlanned(),
            counts.postedOutside(),
            List.of(
                target(postingProps.typical(), judged, counts.postedWithinTypical()),
                target(postingProps.tail(), judged, counts.postedWithinTail())));
    }

    private static Posting.Target target(ControlProperties.Target target, long judged, long within) {
        if (judged == 0) {
            return new Posting.Target(target.threshold().toMillis(), target.target(), within, null, null);
        }
        var allowed = BigDecimal.valueOf(judged).multiply(BigDecimal.ONE.subtract(target.target()));
        return new Posting.Target(
            target.threshold().toMillis(),
            target.target(),
            within,
            BigDecimal.valueOf(within).divide(BigDecimal.valueOf(judged), MathContext.DECIMAL64).doubleValue(),
            BigDecimal.valueOf(judged - within).divide(allowed, MathContext.DECIMAL64).doubleValue());
    }

    private long budget(BigDecimal target) {
        return BigDecimal.valueOf(props.sloWindow().toMinutes()).multiply(BigDecimal.ONE.subtract(target))
            .setScale(0, RoundingMode.DOWN).longValueExact();
    }

    public int deleteOldMinutes(Instant now) {
        minutes.deleteStartsBefore(now.minus(props.minutesRetention()));
        return minutes.deleteMinutesBefore(now.minus(props.minutesRetention()));
    }
}
