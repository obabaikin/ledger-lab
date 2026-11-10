package com.ledgerlab.ledger.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledger.topic")
public record TopicProperties(String name) {
}
