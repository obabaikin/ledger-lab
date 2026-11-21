package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ledgerlab.ledger.kafka.ConsumerProperties;
import com.ledgerlab.ledger.kafka.ConsumerWarmUp;
import com.ledgerlab.ledger.kafka.TransferConsumer;
import com.ledgerlab.ledger.pool.PoolProperties;
import com.ledgerlab.ledger.service.AccountsProperties;
import com.ledgerlab.ledger.service.LedgerService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ConsumerWarmUpTest {

    private static final int ROUNDS = 3;
    private static final int BATCH = 7;
    private static final List<TopicPartition> ONE_PARTITION = List.of(new TopicPartition("transfers", 0));

    private final LedgerService ledger = mock(LedgerService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final KafkaListenerEndpointRegistry listeners = mock(KafkaListenerEndpointRegistry.class);
    private final SimpleTransactionStatus status = new SimpleTransactionStatus();

    private ConsumerWarmUp warmUp(Duration assignmentWait) {
        when(transactions.getTransaction(any())).thenReturn(status);
        when(listeners.getListenerContainer(TransferConsumer.LISTENER_ID)).thenReturn(container);
        var kafka = new KafkaProperties();
        kafka.getConsumer().setMaxPollRecords(BATCH);
        return new ConsumerWarmUp(
            ledger,
            transactions,
            listeners,
            new ConsumerProperties(Duration.ofMillis(1), new ConsumerProperties.WarmUp(ROUNDS, Duration.ofMinutes(1)), assignmentWait),
            new AccountsProperties(100, 1_000, 10),
            new PoolProperties(1_000, 5, Duration.ofHours(1), Duration.ofHours(24), Duration.ofSeconds(1), 100),
            kafka,
            Clock.systemUTC());
    }

    @Test
    void the_consumer_starts_after_the_rounds_and_every_round_is_marked_to_roll_back() throws Exception {
        when(container.getAssignedPartitions()).thenReturn(ONE_PARTITION);

        warmUp(Duration.ofMinutes(1)).run(new DefaultApplicationArguments());

        var order = inOrder(ledger, container);
        order.verify(ledger, times(ROUNDS)).applyBatch(anyList());
        order.verify(container).start();
        // The transaction manager rolls back a status marked rollback-only when it is handed to commit.
        verify(transactions, times(ROUNDS)).commit(status);
        assertThat(status.isRollbackOnly()).isTrue();
    }

    @Test
    void the_start_ends_only_once_the_consumer_holds_a_partition() throws Exception {
        when(container.getAssignedPartitions()).thenReturn(List.of());
        var warmUp = warmUp(Duration.ofMinutes(1));

        var started = CompletableFuture.runAsync(() -> {
            try {
                warmUp.run(new DefaultApplicationArguments());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(container).start());
        TimeUnit.MILLISECONDS.sleep(300);
        assertThat(started).isNotDone();

        when(container.getAssignedPartitions()).thenReturn(ONE_PARTITION);
        await().atMost(Duration.ofSeconds(5)).until(started::isDone);
    }

    @Test
    void a_consumer_that_gets_no_partition_ends_the_start_after_the_wait() throws Exception {
        when(container.getAssignedPartitions()).thenReturn(List.of());

        warmUp(Duration.ofMillis(300)).run(new DefaultApplicationArguments());

        verify(container).start();
    }

    @Test
    void a_failing_round_still_starts_the_consumer() throws Exception {
        when(ledger.applyBatch(anyList())).thenThrow(new IllegalStateException("database down"));
        when(container.getAssignedPartitions()).thenReturn(ONE_PARTITION);

        warmUp(Duration.ofMinutes(1)).run(new DefaultApplicationArguments());

        verify(ledger).applyBatch(anyList());
        verify(container).start();
    }
}
