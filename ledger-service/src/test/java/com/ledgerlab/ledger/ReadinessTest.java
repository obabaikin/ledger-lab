package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ledgerlab.ledger.kafka.AssignedPartitionsGauge;
import com.ledgerlab.ledger.kafka.KafkaAssignmentHealthIndicator;
import com.ledgerlab.ledger.kafka.TransferConsumer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

@AutoConfigureTestRestTemplate
class ReadinessTest extends IntegrationTest {

    private static final String HEALTH = "/actuator/health";
    private static final String READINESS = "/actuator/health/readiness";
    private static final String LIVENESS = "/actuator/health/liveness";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Autowired TestRestTemplate http;
    @Autowired KafkaListenerEndpointRegistry listeners;

    private HttpStatus status(String path) {
        return HttpStatus.valueOf(http.getForEntity(path, String.class).getStatusCode().value());
    }

    @Test
    @DisplayName("stays ready while the transfer consumer holds no partition, and the health shows the consumer down")
    void stays_ready_while_the_transfer_consumer_holds_no_partition_and_the_health_shows_the_consumer_down() {
        var container = Objects.requireNonNull(listeners.getListenerContainer(TransferConsumer.LISTENER_ID));
        await().atMost(TIMEOUT).until(() -> status(HEALTH) == HttpStatus.OK);

        container.stop();
        try {
            assertThat(status(READINESS)).isEqualTo(HttpStatus.OK);
            assertThat(status(LIVENESS)).isEqualTo(HttpStatus.OK);
            assertThat(status(HEALTH)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            container.start();
        }
        await().atMost(TIMEOUT).until(() -> status(HEALTH) == HttpStatus.OK);
    }

    @Test
    void some_of_the_partitions_are_enough_to_be_ready() {
        var container = mock(MessageListenerContainer.class);
        when(container.getAssignedPartitions()).thenReturn(List.of(new TopicPartition("transfers", 0)));
        var registry = mock(KafkaListenerEndpointRegistry.class);
        when(registry.getListenerContainer(TransferConsumer.LISTENER_ID)).thenReturn(container);

        assertThat(new KafkaAssignmentHealthIndicator(registry).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("the partitions an instance holds are published, so a live but idle instance shows")
    void the_partitions_an_instance_holds_are_published_so_a_live_but_idle_instance_shows() {
        var container = mock(MessageListenerContainer.class);
        when(container.getAssignedPartitions()).thenReturn(List.of(new TopicPartition("transfers", 0), new TopicPartition("transfers", 2)));
        var mockedListeners = mock(KafkaListenerEndpointRegistry.class);
        when(mockedListeners.getListenerContainer(TransferConsumer.LISTENER_ID)).thenReturn(container);
        var meters = new SimpleMeterRegistry();

        new AssignedPartitionsGauge(mockedListeners, meters);

        assertThat(meters.get(AssignedPartitionsGauge.METRIC).gauge().value()).isEqualTo(2.0);
        when(container.getAssignedPartitions()).thenReturn(List.of());
        assertThat(meters.get(AssignedPartitionsGauge.METRIC).gauge().value()).isZero();
    }
}
