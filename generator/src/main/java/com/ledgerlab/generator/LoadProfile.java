package com.ledgerlab.generator;

import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Shapes the traffic like a retail bank: most payments go to a few merchants (hot accounts),
 * a small share is deliberately invalid so that rejections show up in the results.
 */
public final class LoadProfile {

    /**
     * Amount that no account can cover, to provoke INSUFFICIENT_FUNDS: 10 000 000 EUR, above a 1 000 000 EUR opening
     * balance plus what a merchant collects in an hour at 300 TPS (about 615 000 EUR), so it is never posted.
     */
    public static final long OVERSIZED_AMOUNT_CENTS = 1_000_000_000L;
    public static final double DEFAULT_HOT_SHARE = 0.3;

    private static final long PPM = 1_000_000L;
    private static final long MIN_AMOUNT_CENTS = 1L;
    private static final long MAX_AMOUNT_CENTS = 2_000L;
    private static final double MAX_INVALID_SHARE = 0.5;

    private final UUID runId;
    private final int accountCount;
    // The last blockedAccounts account numbers are blocked (ledger convention).
    private final int blockedAccounts;
    // Account numbers 1..hotAccounts receive hotShare of all payments.
    private final int hotAccounts;
    private final double hotShare;
    private final Random random;
    private final int lastActiveAccount;
    private final long oversizedPpm;
    private final long blockedPpm;
    private long count = 0L;

    public LoadProfile(UUID runId, int accountCount, int blockedAccounts, int hotAccounts, double oversizedShare, double blockedShare) {
        this(runId, accountCount, blockedAccounts, hotAccounts, DEFAULT_HOT_SHARE, oversizedShare, blockedShare, System.nanoTime());
    }

    public LoadProfile(
        UUID runId,
        int accountCount,
        int blockedAccounts,
        int hotAccounts,
        double hotShare,
        double oversizedShare,
        double blockedShare,
        long seed
    ) {
        this.runId = runId;
        this.accountCount = accountCount;
        this.blockedAccounts = blockedAccounts;
        this.hotAccounts = hotAccounts;
        this.hotShare = hotShare;
        this.random = new Random(seed);
        this.lastActiveAccount = accountCount - blockedAccounts;
        this.oversizedPpm = toPpm(oversizedShare);
        this.blockedPpm = toPpm(blockedShare);
        if (accountCount - blockedAccounts < 2) {
            throw new IllegalArgumentException("at least two active accounts are needed");
        }
        if (blockedAccounts < 0) {
            throw new IllegalArgumentException("Failed requirement.");
        }
        if (hotAccounts < 0 || hotAccounts > lastActiveAccount) {
            throw new IllegalArgumentException("Failed requirement.");
        }
        if (oversizedShare < 0.0 || oversizedShare > MAX_INVALID_SHARE || blockedShare < 0.0 || blockedShare > MAX_INVALID_SHARE) {
            throw new IllegalArgumentException("invalid shares must be at most 0.5 each");
        }
    }

    // java.util.Random would be safe, but the count and the picks of one transfer must not interleave with another's.
    public synchronized PlannedTransfer next() {
        var n = ++count;
        var from = 1 + random.nextInt(lastActiveAccount);
        int to;
        // Half a period apart, so with equal shares a transfer is never picked twice.
        if (picked(n, blockedPpm, PPM / 2) && blockedAccounts > 0) {
            to = lastActiveAccount + 1 + random.nextInt(accountCount - lastActiveAccount);
        } else if (random.nextDouble() < hotShare && hotAccounts > 0) {
            var hot = anyBut(hotAccounts, from);
            to = hot != null ? hot : anyActiveBut(from);
        } else {
            to = anyActiveBut(from);
        }
        var amount = picked(n, oversizedPpm, 0) ? OVERSIZED_AMOUNT_CENTS : random.nextLong(MIN_AMOUNT_CENTS, MAX_AMOUNT_CENTS + 1);
        return new PlannedTransfer(runId, UUID.randomUUID(), from, to, amount);
    }

    /** Integer arithmetic, so that exactly floor(n * share) of the first n transfers are picked. */
    private static boolean picked(long n, long ppm, long phase) {
        return (n * ppm + phase) / PPM > ((n - 1) * ppm + phase) / PPM;
    }

    private int anyActiveBut(int excluded) {
        return Objects.requireNonNull(anyBut(lastActiveAccount, excluded), "with two active accounts there is one other than the sender");
    }

    private @Nullable Integer anyBut(int last, int excluded) {
        var excludedInRange = excluded >= 1 && excluded <= last;
        var size = last - (excludedInRange ? 1 : 0);
        if (size <= 0) {
            return null;
        }
        var pick = 1 + random.nextInt(size);
        return excludedInRange && pick >= excluded ? pick + 1 : pick;
    }

    private static long toPpm(double share) {
        return Math.round(share * PPM);
    }
}
