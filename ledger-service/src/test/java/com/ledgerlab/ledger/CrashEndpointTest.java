package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerlab.ledger.chaos.CrashEndpoint;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CrashEndpointTest {

    private static final Duration HALT_DELAY = Duration.ofMillis(300);

    @Test
    void crash_endpoint_answers_202_and_halts_after_the_delay() throws InterruptedException {
        var halts = new AtomicInteger();
        var endpoint = new CrashEndpoint(halts::incrementAndGet, HALT_DELAY);

        var answer = endpoint.crash();

        assertThat(answer.getStatus()).isEqualTo(202);
        Thread.sleep(HALT_DELAY.dividedBy(2));
        assertThat(halts.get()).isZero();
        await().atMost(HALT_DELAY.multipliedBy(5)).until(() -> halts.get() == 1);
    }
}
