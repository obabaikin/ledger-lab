package com.ledgerlab.gateway.service

import com.ledgerlab.gateway.GatewayProperties
import com.ledgerlab.gateway.domain.Transfer
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * The event wakes a request before the recorder's commit shows in the row, so the request reads until it does.
 * It never writes the result itself: a second writer of the same row waited for the recorder's batch, and the batch for it.
 */
@Component
class RecordedResultReader(private val service: TransferService, props: GatewayProperties, registry: MeterRegistry) {
    private val firstPause = props.waitRead.firstPause
    private val maxReads = props.waitRead.maxReads
    private val reads = DistributionSummary.builder("gateway.wait.reads")
        .description("reads a woken request made until it saw a final status, ran out of reads or its wait ended")
        .publishPercentiles(*READS_PERCENTILES)
        .register(registry)

    /** The row once it is final, or as it stands after the last read or when [deadlineNanos] (of [System.nanoTime]) passes. */
    fun readFinal(transferId: UUID, deadlineNanos: Long): Transfer? {
        var pause = firstPause
        var count = 0
        while (true) {
            val left = Duration.ofNanos((deadlineNanos - System.nanoTime()).coerceAtLeast(0))
            Thread.sleep(minOf(pause, left))
            val row = service.find(transferId)
            count++
            if (row == null || row.status.isFinal || count == maxReads || System.nanoTime() >= deadlineNanos) {
                reads.record(count.toDouble())
                return row
            }
            pause = pause.multipliedBy(PAUSE_GROWTH)
        }
    }

    private companion object {
        const val PAUSE_GROWTH = 2L
        val READS_PERCENTILES = doubleArrayOf(0.5, 0.99)
    }
}
