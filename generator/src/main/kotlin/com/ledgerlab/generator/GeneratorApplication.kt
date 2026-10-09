package com.ledgerlab.generator

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Duration

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class GeneratorApplication

fun main(args: Array<String>) {
    runApplication<GeneratorApplication>(*args)
}

@ConfigurationProperties("generator")
data class GeneratorProperties(
    val gatewayUrl: String,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    /** Attempts per transfer, the first one included; past that the outcome stays unknown (ADR 0006). */
    val maxAttempts: Int,
    /** Pause before the second attempt; it doubles for each further one, with jitter. */
    val retryBackoff: Duration,
    /** Back-pressure: how many requests may be open at once before the pacer stops launching. */
    val maxInFlight: Int,
    /** How long a finished simulation stays available through the API. */
    val retention: Duration,
    val retentionSweepInterval: Duration,
)
