package com.ledgerlab.ledger.kafka;

import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.domain.Account;
import com.ledgerlab.ledger.pool.PoolProperties;
import com.ledgerlab.ledger.service.AccountsProperties;
import com.ledgerlab.ledger.service.LedgerService;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Posts rolled-back batches before the instance joins the consumer group, then starts the transfer consumer and returns
 * once it holds partitions. Spring Boot reports the instance ready only after its runners, so a deploy replaces the
 * next instance only when this one reads; later rebalances do not touch readiness (ADR 0012).
 */
@Component
public class ConsumerWarmUp implements ApplicationRunner {

    private static final Duration ASSIGNMENT_POLL = Duration.ofMillis(100);
    private static final long WARM_UP_AMOUNT_CENTS = 1;

    private static final Logger log = LoggerFactory.getLogger(ConsumerWarmUp.class);

    private final LedgerService ledger;
    private final TransactionTemplate rolledBack;
    private final KafkaListenerEndpointRegistry listeners;
    private final ConsumerProperties props;
    private final AccountsProperties accounts;
    private final PoolProperties pool;
    private final int batchSize;
    private final Clock clock;

    public ConsumerWarmUp(
        LedgerService ledger,
        PlatformTransactionManager transactions,
        KafkaListenerEndpointRegistry listeners,
        ConsumerProperties props,
        AccountsProperties accounts,
        PoolProperties pool,
        KafkaProperties kafka,
        Clock clock
    ) {
        this.ledger = ledger;
        this.rolledBack = new TransactionTemplate(transactions);
        this.listeners = listeners;
        this.props = props;
        this.accounts = accounts;
        this.pool = pool;
        this.batchSize = Objects.requireNonNull(kafka.getConsumer().getMaxPollRecords(), "spring.kafka.consumer.max-poll-records");
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        warmUp();
        var container = Objects.requireNonNull(listeners.getListenerContainer(TransferConsumer.LISTENER_ID), "transfer consumer");
        container.start();
        awaitAssignment();
    }

    private void warmUp() {
        var started = System.nanoTime();
        var deadline = started + props.warmUp().maxDuration().toNanos();
        var done = 0;
        try {
            while (done < props.warmUp().rounds() && System.nanoTime() < deadline) {
                rolledBack.executeWithoutResult(status -> {
                    status.setRollbackOnly();
                    ledger.applyBatch(batch());
                });
                done++;
            }
        } catch (RuntimeException e) {
            log.warn("Warm-up stopped after {} rounds; the consumer starts cold", done, e);
            return;
        }
        var millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (done < props.warmUp().rounds()) {
            log.warn("Warm-up reached its limit of {} after {} of {} rounds", props.warmUp().maxDuration(), done, props.warmUp().rounds());
        } else {
            log.info("Warm-up: {} rounds in {} ms", done, millis);
        }
    }

    /** Away from the hot accounts, which most live batches lock, and from the blocked ones, which reject before posting. */
    private List<TransferCommand> batch() {
        var first = Account.FIRST_CLIENT_ACCOUNT_NO + pool.hotAccounts();
        var last = Math.max(first + 1, accounts.count() - accounts.blocked());
        var random = ThreadLocalRandom.current();
        var now = clock.instant();
        var commands = new ArrayList<TransferCommand>(batchSize);
        for (var i = 0; i < batchSize; i++) {
            var from = random.nextInt(first, last + 1);
            var to = from == last ? first : from + 1;
            commands.add(new TransferCommand(UUID.randomUUID(), from, to, WARM_UP_AMOUNT_CENTS, now));
        }
        return commands;
    }

    private void awaitAssignment() throws InterruptedException {
        var started = System.nanoTime();
        var deadline = started + props.assignmentWait().toNanos();
        while (AssignedPartitions.count(listeners) == 0) {
            if (System.nanoTime() >= deadline) {
                log.warn("No partition assigned within {}; the instance reports ready anyway", props.assignmentWait());
                return;
            }
            Thread.sleep(ASSIGNMENT_POLL);
        }
        log.info("Reading {} partitions {} ms after the consumer started",
            AssignedPartitions.count(listeners), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
}
