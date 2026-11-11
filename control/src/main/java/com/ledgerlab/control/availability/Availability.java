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
    private final DatabaseClock clock;
    private final ControlProperties.Availability props;
    private final ControlProperties.Maintenance maintenance;
    private volatile boolean lastAnswered = true;

    public Availability(AvailabilityRepository minutes, GeneratorClient generator, DatabaseClock clock, ControlProperties props) {
        this.minutes = minutes;
        this.generator = generator;
        this.clock = clock;
        this.props = props.availability();
        this.maintenance = props.maintenance();
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
        minutes.rollUpHours(clock.now(), props.minutesRetention(), props.goodMinuteAnsweredPercent(), maintenance.notice());
    }

    public Slo slo(Instant now) {
        var counts = minutes.countsSince(now.minus(props.sloWindow()), props.goodMinuteAnsweredPercent(), maintenance.notice());
        var judged = counts.measuredMinutes() - counts.plannedMinutes();
        var budget = BigDecimal.valueOf(props.sloWindow().toMinutes()).multiply(BigDecimal.ONE.subtract(props.sloTarget()))
            .setScale(0, RoundingMode.DOWN).longValueExact();
        return new Slo(
            props.sloTarget(),
            (int) props.sloWindow().toDays(),
            counts.measuredMinutes(),
            counts.badMinutes(),
            budget,
            counts.plannedMinutes(),
            judged == 0 ? null : (double) (judged - counts.badMinutes()) / judged,
            counts.total() == 0 ? null : (double) counts.busy() / counts.total());
    }

    public int deleteOldMinutes(Instant now) {
        return minutes.deleteMinutesBefore(now.minus(props.minutesRetention()));
    }
}
