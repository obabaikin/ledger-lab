package com.ledgerlab.generator;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstanceFactoryContext;
import org.junit.jupiter.api.extension.TestInstancePreConstructCallback;

/**
 * A test reads the current period (an hour in tests) more than once, and a new hour between two reads makes it read two
 * periods. Close to the hour's end the test starts after it instead; before its instance is made, as the instance
 * takes the time in its fields.
 */
public class AwayFromTheHoursEnd implements TestInstancePreConstructCallback {

    private static final Duration LONGEST_TEST = Duration.ofSeconds(30);

    @Override
    public void preConstructTestInstance(TestInstanceFactoryContext factoryContext, ExtensionContext context) throws Exception {
        var now = Instant.now();
        var nextHour = now.truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1));
        var left = Duration.between(now, nextHour);
        if (left.compareTo(LONGEST_TEST) < 0) {
            Thread.sleep(left.plusSeconds(1));
        }
    }
}
