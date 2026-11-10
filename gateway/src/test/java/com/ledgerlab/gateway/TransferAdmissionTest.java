package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerlab.gateway.service.GatewayBusyException;
import com.ledgerlab.gateway.service.TransferAdmission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransferAdmissionTest {

    static final GatewayProperties PROPS = props(new GatewayProperties.Accept(1, 1, Duration.ofSeconds(5)));

    static GatewayProperties props(GatewayProperties.Accept accept) {
        return new GatewayProperties(
            "transfers", "results", new GatewayProperties.Outbox(100, Duration.ofSeconds(1)),
            new GatewayProperties.Consumer(Duration.ofSeconds(1)), Duration.ofSeconds(10),
            new GatewayProperties.WaitRead(Duration.ofMillis(25), 5), Duration.ofSeconds(1),
            Duration.ofSeconds(5), new GatewayProperties.Inflight(1), new GatewayProperties.Admission(1, Duration.ofMillis(10)), accept,
            new GatewayProperties.WarmUp(0, Duration.ZERO, Duration.ZERO));
    }

    @Test
    void a_submission_that_throws_gives_its_permit_back() throws InterruptedException {
        var admission = new TransferAdmission(PROPS, new SimpleMeterRegistry());

        assertThatThrownBy(() -> admission.admit(() -> {
            throw new IllegalStateException("database down");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(admission.admit(() -> "taken")).isEqualTo("taken");
    }

    @Test
    void with_every_permit_held_the_next_submission_is_refused_as_busy() throws InterruptedException {
        var admission = new TransferAdmission(PROPS, new SimpleMeterRegistry());

        admission.admit(() -> assertThatThrownBy(() -> admission.admit(() -> "second")).isInstanceOf(GatewayBusyException.class));
    }

    @Test
    @DisplayName("an open submission over the in-flight limit is refused at once, and a finished one gives its place back")
    void an_open_submission_over_the_in_flight_limit_is_refused_at_once_and_a_finished_one_gives_its_place_back() throws InterruptedException {
        var admission = new TransferAdmission(PROPS, new SimpleMeterRegistry());

        admission.enter(() -> assertThatThrownBy(() -> admission.enter(() -> "second")).isInstanceOf(GatewayBusyException.class));
        assertThatThrownBy(() -> admission.enter(() -> {
            throw new IllegalStateException("ledger down");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(admission.enter(() -> "taken")).isEqualTo("taken");
    }
}
