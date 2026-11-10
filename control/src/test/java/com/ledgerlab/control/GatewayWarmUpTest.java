package com.ledgerlab.control;

import static com.ledgerlab.control.RunFixtures.GATEWAY_ALL_WARM;
import static com.ledgerlab.control.RunFixtures.prometheus;
import static com.ledgerlab.control.RunFixtures.sample;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.gateway.GatewayWarmUp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GatewayWarmUpTest extends IntegrationTest {

    private static final String QUERY_PATH = "/api/v1/query";

    @Autowired GatewayWarmUp warmUp;

    private void lowest(int value) {
        prometheus.answerQuery("GET", QUERY_PATH, "query=" + GATEWAY_ALL_WARM, 200, prometheus(List.of(sample(Map.of(), value))));
    }

    @Test
    @DisplayName("warm when every gateway instance Prometheus scrapes finished its warm-up")
    void warm_when_every_gateway_instance_Prometheus_scrapes_finished_its_warm_up() throws Exception {
        lowest(1);

        assertThat(warmUp.check()).isTrue();
    }

    @Test
    void not_warm_while_any_instance_still_warms_up() throws Exception {
        lowest(0);

        assertThat(warmUp.check()).isFalse();
    }

    @Test
    void not_warm_when_no_gateway_instance_is_scraped() throws Exception {
        prometheus.answerQuery("GET", QUERY_PATH, "query=" + GATEWAY_ALL_WARM, 200, prometheus(List.of()));

        assertThat(warmUp.check()).isFalse();
    }

    @Test
    @DisplayName("not warm when Prometheus cannot be read, even after it said warm")
    void not_warm_when_Prometheus_cannot_be_read_even_after_it_said_warm() throws Exception {
        lowest(1);
        warmUp.check();

        prometheus.reset();
        prometheus.status = 503;

        assertThat(warmUp.check()).isFalse();
    }
}
