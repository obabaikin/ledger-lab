package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ledgerlab.ledger.kafka.ConsumerProperties;
import com.ledgerlab.ledger.kafka.ConsumerStart;
import com.ledgerlab.ledger.kafka.TransferConsumer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

class ConsumerStartTest {

    private static final List<TopicPartition> ONE_PARTITION = List.of(new TopicPartition("transfers", 0));

    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final KafkaListenerEndpointRegistry listeners = mock(KafkaListenerEndpointRegistry.class);

    private ConsumerStart consumerStart(Duration assignmentWait) {
        when(listeners.getListenerContainer(TransferConsumer.LISTENER_ID)).thenReturn(container);
        return new ConsumerStart(listeners, new ConsumerProperties(Duration.ofMillis(1), assignmentWait));
    }

    @Test
    void the_start_ends_only_once_the_consumer_holds_a_partition() throws Exception {
        when(container.getAssignedPartitions()).thenReturn(List.of());
        var consumerStart = consumerStart(Duration.ofMinutes(1));

        var started = CompletableFuture.runAsync(() -> {
            try {
                consumerStart.run(new DefaultApplicationArguments());
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

        consumerStart(Duration.ofMillis(300)).run(new DefaultApplicationArguments());

        verify(container).start();
    }
}
