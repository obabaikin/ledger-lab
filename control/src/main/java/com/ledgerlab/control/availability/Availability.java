package com.ledgerlab.control.availability;

import com.ledgerlab.control.ControlProperties;
import com.ledgerlab.control.DatabaseClock;
import com.ledgerlab.control.generator.GeneratorClient;
import com.ledgerlab.control.generator.MinutesPage;
import com.ledgerlab.control.http.Reply;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
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
    private final FreshnessCollector freshness;
    private final DatabaseClock clock;
    private final ControlProperties.Availability props;
    private final ControlProperties.Freshness freshnessProps;
    private final AvailabilityRepository.Rules rules;
    private volatile boolean lastAnswered = true;

    public Availability(
        AvailabilityRepository minutes,
        GeneratorClient generator,
        FreshnessCollector freshness,
        DatabaseClock clock,
        ControlProperties props
    ) {
        this.minutes = minutes;
        this.generator = generator;
        this.freshness = freshness;
        this.clock = clock;
        this.props = props.availability();
        this.freshnessProps = props.freshness();
        var outside = props.outside();
        this.rules = new AvailabilityRepository.Rules(
            this.props.goodMinuteAnsweredPercent(), props.maintenance().notice(), freshnessProps.badSharePercent(), outside.guaranteedTps(),
            outside.chaosAftermath(), outside.loadAftermath());
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
        freshness.collect(clock.now());
        minutes.rollUpHours(clock.now(), props.minutesRetention(), rules);
    }

    public Slo slo(Instant now) {
        return slo(counts(now));
    }

    /** Both objectives from one read of the minutes, so that they cover the same ones. */
    public Objectives objectives(Instant now) {
        var counts = counts(now);
        return new Objectives(slo(counts), freshness(counts));
    }

    public record Objectives(Slo slo, Freshness freshness) {
    }

    private AvailabilityRepository.Counts counts(Instant now) {
        return minutes.countsSince(now.minus(props.sloWindow()), rules);
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

    private Freshness freshness(AvailabilityRepository.Counts counts) {
        return new Freshness(
            freshnessProps.sloTarget(),
            freshnessProps.threshold().toMillis(),
            counts.freshBad(),
            budget(freshnessProps.sloTarget()),
            counts.freshPlanned(),
            counts.freshOutside());
    }

    private long budget(BigDecimal target) {
        return BigDecimal.valueOf(props.sloWindow().toMinutes()).multiply(BigDecimal.ONE.subtract(target))
            .setScale(0, RoundingMode.DOWN).longValueExact();
    }

    public int deleteOldMinutes(Instant now) {
        return minutes.deleteMinutesBefore(now.minus(props.minutesRetention()));
    }
}
