package com.ledgerlab.ledger.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledger.deferred-credits")
public record DeferredCreditProperties(Duration applyInterval, int batchSize) {
}
