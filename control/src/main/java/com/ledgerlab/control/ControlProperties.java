package com.ledgerlab.control;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("control")
public record ControlProperties(
    Runs runs,
    Ledger ledger,
    Gateway gateway,
    Generator generator,
    Services services,
    Cleanup cleanup,
    Reporting reporting,
    Prometheus prometheus,
    Chaos chaos,
    Invites invites,
    Turnstile turnstile,
    Mail mail,
    Site site
) {
    public record Runs(
        int maxActive,
        int maxQueued,
        Duration queueTimeout,
        List<Integer> tpsOptions,
        int defaultTps,
        List<Duration> durationOptions,
        Duration setupTimeout,
        Duration runningGrace,
        Duration drainStallTimeout,
        Duration drainTimeout,
        Accounts accounts,
        int maxTotalTps,
        // At most this many starts wait for the queue's lock at once; one more waits up to startWait, then is refused.
        int maxConcurrentStarts,
        Duration startWait
    ) {
        public Runs {
            if (!tpsOptions.stream().allMatch(it -> it <= maxTotalTps)) {
                throw new IllegalArgumentException("a run of " + Collections.max(tpsOptions) + " TPS would never fit max-total-tps " + maxTotalTps);
            }
            if (!tpsOptions.contains(defaultTps)) {
                throw new IllegalArgumentException("default-tps " + defaultTps + " is not one of tps-options " + tpsOptions);
            }
        }

        public List<Integer> durationSecondsOptions() {
            return durationOptions.stream().map(it -> (int) it.toSeconds()).toList();
        }
    }

    public record Accounts(int count, long openingBalanceCents, int blocked, long dailyLimitCents) {
    }

    public record Ledger(String baseUrl, int transferPartitions, Duration reconciliationTimeout) {
    }

    public record Gateway(String baseUrl, Duration summaryTimeout) {
    }

    public record Generator(String baseUrl, Duration reconciliationTimeout) {
    }

    public record Services(Duration requestTimeout) {
    }

    public record Cleanup(Duration interval, Duration retention) {
    }

    public record Reporting(String baseUrl, Duration catchUpWait, Duration finalStatsTimeout) {
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

    public record Invites(InviteEmail email) {
    }

    public record InviteEmail(
        int linksPerAddress,
        int dailyMails,
        Duration window,
        Duration lifetime,
        int runsPerHour,
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
