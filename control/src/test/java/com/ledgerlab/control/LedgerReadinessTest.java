package com.ledgerlab.control;

import static com.ledgerlab.control.RunFixtures.prometheus;
import static com.ledgerlab.control.RunFixtures.sample;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.control.ledger.LedgerReadiness;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LedgerReadinessTest extends IntegrationTest {

    private static final String QUERY_PATH = "/api/v1/query";
    private static final String HELD = "sum(ledger_kafka_assigned_partitions)";

    @Autowired LedgerReadiness readiness;

    private void held(int partitions) {
        prometheus.answerQuery("GET", QUERY_PATH, "query=" + HELD, 200, prometheus(List.of(sample(Map.of(), partitions))));
    }

    @Test
    void ready_when_the_live_instances_hold_every_partition() throws Exception {
        held(props.ledger().transferPartitions());

        assertThat(readiness.isReady()).isTrue();
    }

    @Test
    @DisplayName("not ready while a killed instance still holds its partitions, though the live ones hold some")
    void not_ready_while_a_killed_instance_still_holds_its_partitions_though_the_live_ones_hold_some() throws Exception {
        held(props.ledger().transferPartitions() - 2);

        assertThat(readiness.isReady()).isFalse();
    }

    @Test
    @DisplayName("not ready when more partitions are counted than there are, as in a rebalance between scrapes")
    void not_ready_when_more_partitions_are_counted_than_there_are_as_in_a_rebalance_between_scrapes() throws Exception {
        held(props.ledger().transferPartitions() + 2);

        assertThat(readiness.isReady()).isFalse();
    }

    @Test
    void not_ready_when_no_ledger_instance_is_scraped() throws Exception {
        prometheus.answerQuery("GET", QUERY_PATH, "query=" + HELD, 200, prometheus(List.of()));

        assertThat(readiness.isReady()).isFalse();
    }

    @Test
    void not_ready_when_Prometheus_cannot_be_read() throws Exception {
        prometheus.status = 503;

        assertThat(readiness.isReady()).isFalse();
    }
}
