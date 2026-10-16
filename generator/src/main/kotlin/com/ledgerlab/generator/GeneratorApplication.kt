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
    val maxInFlight: Int,
    val retention: Duration,
    val journal: JournalProperties,
    val profile: ProfileProperties,
)

data class ProfileProperties(
    val duplicateShare: Double,
    /** Delay of a duplicate after the first send starts, picked from this list; it may overlap that send. */
    val duplicateDelays: List<Duration>,
    /** Share of transfers no account can cover, picked by count so a run has exactly that many. */
    val oversizedShare: Double,
    val blockedShare: Double,
)

data class JournalProperties(
    /** How long after its run ends a journal is kept: long enough for the client reconciliation. */
    val retention: Duration,
    /** Entries all journals in memory may take together; a start beyond it is refused, never an OOM. */
    val maxEntries: Long,
)
