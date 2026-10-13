package com.ledgerlab.reporting

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("reporting")
data class ReportingProperties(
    val transfersTopic: String,
    val resultsTopic: String,
    val consumer: Consumer,
) {
    data class Consumer(val retryBackoff: Duration)
}
