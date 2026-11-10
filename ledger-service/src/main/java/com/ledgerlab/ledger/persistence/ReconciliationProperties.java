package com.ledgerlab.ledger.persistence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledger.reconciliation")
public record ReconciliationProperties(Duration statementTimeout) {
}
