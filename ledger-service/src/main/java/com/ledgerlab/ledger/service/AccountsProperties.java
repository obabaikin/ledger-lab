package com.ledgerlab.ledger.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledger.accounts")
public record AccountsProperties(int count, long openingBalanceCents, int blocked) {
}
