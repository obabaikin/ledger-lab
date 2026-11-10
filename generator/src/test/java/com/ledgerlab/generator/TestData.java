package com.ledgerlab.generator;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

final class TestData {

    private static final double INVALID_SHARE = 0.005;

    private TestData() {
    }

    static GeneratorProperties properties(String url) {
        return properties(url, 3, Duration.ofMillis(50));
    }

    static GeneratorProperties properties(String url, int maxAttempts, Duration retryBackoff) {
        return properties(url, maxAttempts, retryBackoff, new JournalProperties(Duration.ofMinutes(10), 300_000));
    }

    static GeneratorProperties properties(String url, int maxAttempts, Duration retryBackoff, JournalProperties journal) {
        return new GeneratorProperties(
            url, Duration.ofSeconds(2), Duration.ofSeconds(10), maxAttempts, retryBackoff, 500,
            Duration.ofSeconds(30), Duration.ofHours(1), journal, new ProfileProperties(0.0, List.of(), INVALID_SHARE, INVALID_SHARE));
    }

    static GatewayClient client(String url) {
        return client(url, 3, Duration.ofMillis(50));
    }

    static GatewayClient client(String url, int maxAttempts, Duration retryBackoff) {
        return new GatewayClient(properties(url, maxAttempts, retryBackoff), new ObjectMapper(), new SimpleMeterRegistry());
    }

    static LoadProfile profile(int accountCount, int blockedAccounts, int hotAccounts) {
        return new LoadProfile(UUID.randomUUID(), accountCount, blockedAccounts, hotAccounts, INVALID_SHARE, INVALID_SHARE);
    }

    static LoadProfile seededProfile(int accountCount, int blockedAccounts, int hotAccounts, double hotShare, long seed) {
        return new LoadProfile(UUID.randomUUID(), accountCount, blockedAccounts, hotAccounts, hotShare, INVALID_SHARE, INVALID_SHARE, seed);
    }

    static PlannedTransfer transfer() {
        return new PlannedTransfer(UUID.randomUUID(), UUID.randomUUID(), 1, 2, 100);
    }

    static JournalFilter anyEntry() {
        return new JournalFilter(null, null, null);
    }
}
