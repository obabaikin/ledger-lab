package com.ledgerlab.reporting.kafka;

import com.ledgerlab.common.Observability;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class BankLatency {

    public static final String METRIC = "reporting.bank.latency";

    private final Timer timer;

    public BankLatency(MeterRegistry registry) {
        this.timer = Timer.builder(METRIC)
            .description("From the gateway accepting a transfer to the ledger's result, by the result events read")
            .publishPercentiles(Observability.latencyPercentiles())
            .register(registry);
    }

    public void record(Instant acceptedAt, Instant processedAt) {
        timer.record(Duration.between(acceptedAt, processedAt));
    }
}
