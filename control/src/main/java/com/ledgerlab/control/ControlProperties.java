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
     * bank's too: a period older than it cannot be reconciled any more.
     */
    public record Periods(
        Duration length,
        Duration closeDelay,
        Duration drainStallTimeout,
        Duration drainTimeout,
        Duration requestRetry,
        Duration detailsRetention
    ) {
        public Periods {
            if (length.isZero() || length.isNegative() || !length.equals(Duration.ofMinutes(length.toMinutes()))) {
                throw new IllegalArgumentException("periods.length must be whole minutes, got " + length);
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

    /** A window declared later than {@link #notice} before its start does not excuse its bad minutes. */
    public record Maintenance(Duration notice) {
    }

    public record Series(Duration range, Duration step, Duration cache) {
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
