package com.ledgerlab.generator

import java.time.Duration

fun testProperties(
    url: String,
    maxAttempts: Int = 3,
    retryBackoff: Duration = Duration.ofMillis(50),
    journal: JournalProperties = JournalProperties(Duration.ofMinutes(10), 300_000),
) = GeneratorProperties(
    url, Duration.ofSeconds(2), Duration.ofSeconds(10), maxAttempts, retryBackoff, 500,
    Duration.ofHours(1), Duration.ofMinutes(5), journal,
)
