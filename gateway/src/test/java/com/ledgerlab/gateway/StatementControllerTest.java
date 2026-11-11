package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ledgerlab.gateway.api.InvalidStatementQueryException;
import com.ledgerlab.gateway.api.StatementController;
import com.ledgerlab.gateway.service.PeriodService;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StatementControllerTest {

    private static final Instant PERIOD_START = Instant.parse("2026-11-10T10:00:00Z");
    private static final String VALID_CURSOR = "1762768800000000_3f0e4f0e-0000-4000-8000-000000000001";

    private final PeriodService periods = mock(PeriodService.class);
    private final StatementController controller = new StatementController(periods);

    @Test
    void a_failure_while_reading_the_statement_is_not_reported_as_the_clients_invalid_cursor() {
        var serverFault = new IllegalArgumentException("No enum constant for a status of a newer schema");
        when(periods.statement(any(), any(), anyInt())).thenThrow(serverFault);

        assertThatThrownBy(() -> controller.statement(PERIOD_START, VALID_CURSOR, null)).isSameAs(serverFault);
    }

    @Test
    void a_text_that_is_not_a_cursor_is_the_clients_invalid_query() {
        assertThatThrownBy(() -> controller.statement(PERIOD_START, "garbage", null)).isInstanceOf(InvalidStatementQueryException.class);
    }
}
