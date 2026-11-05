package com.ledgerlab.ledger.service

import com.ledgerlab.ledger.persistence.AccountRepository
import com.ledgerlab.ledger.persistence.DeferredCreditRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

@ConfigurationProperties("ledger.deferred-credits")
data class DeferredCreditProperties(val applyInterval: Duration, val batchSize: Int)

@Component
class DeferredCreditApplier(private val batch: DeferredCreditBatch, private val props: DeferredCreditProperties) {

    @Scheduled(fixedDelayString = "\${ledger.deferred-credits.apply-interval}")
    fun applyPending() {
        do {
            val applied = batch.applyNext()
        } while (applied == props.batchSize)
    }
}

@Component
class DeferredCreditBatch(
    private val credits: DeferredCreditRepository,
    private val accounts: AccountRepository,
    private val props: DeferredCreditProperties,
    registry: MeterRegistry,
) {
    private val delay = Timer.builder("ledger.credit.delay")
        .description("From posting a transfer to its credit reaching the receiver's balance")
        .publishPercentileHistogram()
        .register(registry)

    /** Returns how many credits it applied; 0 also when another applier holds the lock. */
    @Transactional
    fun applyNext(): Int {
        if (!credits.tryLockApplier()) return 0
        val applied = credits.takePending(props.batchSize)
        if (applied.isEmpty()) return 0
        accounts.addToBalances(applied.groupingBy { it.account }.fold(0L) { sum, credit -> sum + credit.amountCents })
        applied.forEach { delay.record(it.waited) }
        return applied.size
    }
}
