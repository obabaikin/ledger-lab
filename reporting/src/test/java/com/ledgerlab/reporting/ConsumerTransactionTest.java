package com.ledgerlab.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerlab.reporting.kafka.TransferEventsConsumer;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.ObjectMapper;

/** A transfer's row and the numbers of its second are written together or not at all: a batch read again adds once. */
class ConsumerTransactionTest extends IntegrationTest {

    private static final String REFUSE_NUMBERS = "ALTER TABLE second_stats ADD CONSTRAINT refuse_numbers CHECK (false) NOT VALID";

    @Autowired TransferEventsConsumer consumer;
    @Autowired ObjectMapper objectMapper;

    @AfterEach
    void numbersTakenAgain() {
        jdbcTemplate.update("ALTER TABLE second_stats DROP CONSTRAINT IF EXISTS refuse_numbers");
    }

    @Test
    void a_command_whose_numbers_cannot_be_written_leaves_no_row() {
        var command = command(100, Instant.now());
        jdbcTemplate.update(REFUSE_NUMBERS);

        assertThatThrownBy(() -> consumer.onCommands(List.of(objectMapper.writeValueAsString(command))))
            .isInstanceOf(DataAccessException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transfer_stats", Long.class)).isZero();
    }

    @Test
    void a_result_whose_numbers_cannot_be_written_leaves_the_row_without_its_outcome() {
        var command = command(100, Instant.now());
        store(command);
        jdbcTemplate.update(REFUSE_NUMBERS);

        assertThatThrownBy(() -> consumer.onResults(List.of(objectMapper.writeValueAsString(posted(command, Instant.now())))))
            .isInstanceOf(DataAccessException.class);

        assertThat(jdbcTemplate.queryForList("SELECT outcome FROM transfer_stats", String.class)).containsExactly((String) null);
    }
}
