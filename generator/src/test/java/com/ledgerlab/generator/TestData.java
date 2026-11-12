package com.ledgerlab.generator;

import com.ledgerlab.generator.gateway.GatewayClient;
import com.ledgerlab.generator.load.LoadProfile;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class TestData {

    private static final double INVALID_SHARE = 0.005;

    private TestData() {
    }

    /** The generator's settings for a test, each with a default a test may change. */
    public static final class Settings {
        private final String url;
        private int maxAttempts = 3;
        private Duration retryBackoff = Duration.ofMillis(50);
        private int maxInFlight = 500;
        private int tps = 100;
        private double waitShare = 0.0;
        private List<Duration> duplicateDelays = List.of();

        private Settings(String url) {
            this.url = url;
        }

        public Settings maxAttempts(int attempts) {
            this.maxAttempts = attempts;
            return this;
        }

        public Settings retryBackoff(Duration backoff) {
            this.retryBackoff = backoff;
            return this;
        }

        public Settings maxInFlight(int requests) {
            this.maxInFlight = requests;
            return this;
        }

        public Settings tps(int tps) {
            this.tps = tps;
            return this;
        }

        public Settings waitShare(double share) {
            this.waitShare = share;
            return this;
        }

        /** Every transfer is sent twice, the second time after one of these delays. */
        public Settings duplicates(Duration... delays) {
            this.duplicateDelays = List.of(delays);
            return this;
        }

        public GeneratorProperties build() {
            return new GeneratorProperties(
                url, Duration.ofSeconds(2), Duration.ofSeconds(10), maxAttempts, retryBackoff, maxInFlight,
                new GeneratorProperties.Load(true, tps, 2_000, Duration.ofMinutes(10), waitShare, Duration.ofSeconds(1), Duration.ofHours(48)),
                new GeneratorProperties.Accounts(20, 2, 3),
                new GeneratorProperties.Journal(500, Duration.ofMillis(5)),
                new GeneratorProperties.Periods(Duration.ofHours(1), Duration.ofHours(4), Duration.ofHours(48), Duration.ofMinutes(1), Duration.ofSeconds(2), 100),
                new GeneratorProperties.Reconciliation(Duration.ofSeconds(60), 20, 16),
                new ProfileProperties(duplicateDelays.isEmpty() ? 0.0 : 1.0, duplicateDelays, INVALID_SHARE, INVALID_SHARE));
        }

        public GatewayClient client(MeterRegistry registry) {
            return new GatewayClient(build(), new ObjectMapper(), registry);
        }

        public GatewayClient client() {
            return client(new SimpleMeterRegistry());
        }
    }

    public static Settings settings(String url) {
        return new Settings(url);
    }

    public static GatewayClient client(String url) {
        return settings(url).client();
    }

    public static LoadProfile profile(int accountCount, int blockedAccounts, int hotAccounts) {
        return new LoadProfile(accountCount, blockedAccounts, hotAccounts, INVALID_SHARE, INVALID_SHARE);
    }

    public static LoadProfile seededProfile(int accountCount, int blockedAccounts, int hotAccounts, double hotShare, long seed) {
        return new LoadProfile(accountCount, blockedAccounts, hotAccounts, hotShare, INVALID_SHARE, INVALID_SHARE, seed);
    }

    public static PlannedTransfer transfer() {
        return new PlannedTransfer(UUID.randomUUID(), 1, 2, 100);
    }
}
