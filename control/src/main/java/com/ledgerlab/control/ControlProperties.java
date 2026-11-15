package com.ledgerlab.control;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("control")
public record ControlProperties(
    Periods periods,
    Bank bank,
    Availability availability,
    Freshness freshness,
    Outside outside,
    Maintenance maintenance,
    Series series,
    Ledger ledger,
    Gateway gateway,
    Generator generator,
    Services services,
    Cleanup cleanup,
    Reporting reporting,
    Prometheus prometheus,
    Chaos chaos,
    Load load,
    Invites invites,
    Turnstile turnstile,
    Mail mail,
    Site site
) {
    /**
     * {@link #length} is the bank's: every service cuts its periods by the same setting. {@link #detailsRetention} is the
     * bank's too: a period older than it cannot be reconciled any more. The other times count from a period's end: the
     * next period closes at {@code length + closeDelay}, and control settles the oldest period first, so a period's verdict
     * is due before that.
     */
    public record Periods(
        Duration length,
        Duration closeDelay,
        Duration settleDelay,
        Duration checkWait,
        Duration retryWait,
        Duration verdictDeadline,
        Duration detailsRetention,
        Duration countTimeout
    ) {
        public Periods {
            if (length.isZero() || length.isNegative() || !length.equals(Duration.ofMinutes(length.toMinutes()))) {
                throw new IllegalArgumentException("periods.length must be whole minutes, got " + length);
            }
            if (!closeDelay.isPositive() || !checkWait.isPositive() || !retryWait.isPositive() || !countTimeout.isPositive()) {
                throw new IllegalArgumentException(
                    "periods.close-delay, check-wait, retry-wait and count-timeout must be positive, got " + closeDelay + ", " + checkWait + ", "
                        + retryWait + ", " + countTimeout);
            }
            if (closeDelay.compareTo(settleDelay) >= 0 || settleDelay.compareTo(verdictDeadline) >= 0
                || verdictDeadline.compareTo(length.plus(closeDelay)) >= 0) {
                throw new IllegalArgumentException(
                    "periods need close-delay < settle-delay < verdict-deadline < length + close-delay, got " + closeDelay + " < " + settleDelay
                        + " < " + verdictDeadline + " < " + length.plus(closeDelay));
            }
        }
    }

    public record Bank(Duration booksInterval, Duration recentChaos) {
    }

    public record Availability(
        int goodMinuteAnsweredPercent,
        Duration minutesRetention,
        BigDecimal sloTarget,
        Duration sloWindow
    ) {
    }

    /**
     * A minute is bad if more than {@link #badSharePercent} of its transfers took longer than {@link #threshold} from
     * acceptance to result. A minute is read from monitoring once it ended {@link #lag} ago, and no later than
     * {@link #catchUp} after; one not read by then stays without data.
     */
    public record Freshness(Duration threshold, int badSharePercent, Duration lag, Duration catchUp, BigDecimal sloTarget) {
    }

    /**
     * Bad minutes during a crash and {@link #chaosAftermath} after it, and during a pulse above {@link #guaranteedTps}
     * and {@link #loadAftermath} after it, are outside the bounds the bank promises, unless a service restarted in place
     * then, other than the crash's own pods once each: that is the bank falling over, judged as any other minute. A
     * process starts some tens of seconds after it went down, so starts up to {@link #restartMargin} after the end count.
     */
    public record Outside(int guaranteedTps, Duration chaosAftermath, Duration loadAftermath, Duration restartMargin) {
    }

    /** A window declared later than {@link #notice} before its start does not excuse its bad minutes. */
    public record Maintenance(Duration notice) {
    }

    /** Each answer is kept for {@link #cache}, one per range. */
    public record Series(List<Range> ranges, Duration defaultRange, Duration cache) {
        public Series {
            ranges = List.copyOf(ranges);
            if (ranges.stream().noneMatch(it -> it.range().equals(defaultRange))) {
                throw new IllegalArgumentException("series.default-range " + defaultRange + " is not one of the ranges");
            }
        }
    }

    /** A chart of {@link #range} has a point every {@link #step}; each rate is taken over {@link #window}. */
    public record Range(Duration range, Duration step, Duration window) {
    }

    public record Ledger(String baseUrl) {
    }

    public record Gateway(String baseUrl) {
    }

    public record Generator(String baseUrl) {
    }

    public record Services(Duration requestTimeout) {
    }

    public record Cleanup(Duration interval, Duration retention) {
    }

    public record Reporting(String baseUrl, Duration catchUpWait) {
    }

    public record Prometheus(String baseUrl, Duration requestTimeout) {
    }

    public record Chaos(
        boolean enabled,
        Duration minInterval,
        Duration requestTimeout,
        @DefaultValue List<LedgerInstance> ledgerInstances
    ) {
    }

    public record LedgerInstance(String name, String url) {
    }

    /** {@link #minInterval} runs from a pulse's end, not its start. */
    public record Load(
        int minTps,
        int maxTps,
        int tpsStep,
        int defaultTps,
        List<Duration> durationOptions,
        Duration defaultDuration,
        Duration minInterval
    ) {
        public Load {
            if (minTps < 1 || tpsStep < 1 || maxTps < minTps || (maxTps - minTps) % tpsStep != 0) {
                throw new IllegalArgumentException(
                    "load: min-tps " + minTps + " to max-tps " + maxTps + " must be whole steps of tps-step " + tpsStep);
            }
            durationOptions = List.copyOf(durationOptions);
            if (durationOptions.isEmpty() || durationOptions.stream().anyMatch(it -> !isWholeSeconds(it))) {
                throw new IllegalArgumentException("load.duration-options must be whole seconds, got " + durationOptions);
            }
            if (!tpsOptionsOf(minTps, maxTps, tpsStep).contains(defaultTps)) {
                throw new IllegalArgumentException("load.default-tps " + defaultTps + " is not one of the options");
            }
            if (!durationOptions.contains(defaultDuration)) {
                throw new IllegalArgumentException("load.default-duration " + defaultDuration + " is not one of " + durationOptions);
            }
            if (minInterval == null || !minInterval.isPositive()) {
                throw new IllegalArgumentException("load.min-interval must be set and positive, got " + minInterval);
            }
        }

        public List<Integer> tpsOptions() {
            return tpsOptionsOf(minTps, maxTps, tpsStep);
        }

        public List<Integer> secondsOptions() {
            return durationOptions.stream().map(it -> (int) it.toSeconds()).toList();
        }

        private static List<Integer> tpsOptionsOf(int minTps, int maxTps, int tpsStep) {
            return IntStream.rangeClosed(0, (maxTps - minTps) / tpsStep).map(it -> minTps + it * tpsStep).boxed().toList();
        }

        private static boolean isWholeSeconds(Duration duration) {
            return duration.isPositive() && duration.equals(Duration.ofSeconds(duration.toSeconds()));
        }
    }

    public record Invites(InviteEmail email) {
    }

    public record InviteEmail(
        int linksPerAddress,
        int dailyMails,
        Duration window,
        Duration lifetime,
        Duration retention,
        Duration deletionCheck
    ) {
    }

    /** An empty {@link #secret} switches the check off, as locally and in tests. */
    public record Turnstile(String siteKey, String secret, String baseUrl, Duration requestTimeout) {
    }

    /** An empty {@link #apiKey} logs each mail as not sent instead of sending it. */
    public record Mail(
        String baseUrl,
        String apiKey,
        String apiSecret,
        String from,
        Duration sendInterval,
        int batchSize,
        int maxAttempts,
        Duration firstRetry,
        Duration requestTimeout,
        ZoneId timeZone
    ) {
    }

    /** {@link #baseUrl} is the page's address in the mailed links; the operator's details fill the imprint and privacy pages. */
    public record Site(
        String baseUrl,
        String operatorName,
        String operatorAddress,
        String contactEmail,
        String version
    ) {
    }
}
