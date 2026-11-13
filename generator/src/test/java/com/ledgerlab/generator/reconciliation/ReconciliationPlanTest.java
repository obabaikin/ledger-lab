package com.ledgerlab.generator.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.generator.IntegrationTest;
import com.ledgerlab.generator.bank.StatementLine;
import com.ledgerlab.generator.bank.TransferStatus;
import com.ledgerlab.generator.journal.JournalOutcome;
import com.ledgerlab.generator.journal.Period;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The comparison's counting queries are planned the way the comparison sets them up: each side read once and joined
 * through a hash, never a subquery or an index probe per row, so their time does not depend on the planner's view of
 * the cache.
 */
class ReconciliationPlanTest extends IntegrationTest {

    private static final int TRANSFERS = 1_000;

    @Autowired DataSource dataSource;

    @Test
    void the_line_counts_join_each_side_once_through_a_hash() throws SQLException {
        assertJoinedOnceThroughAHash(StatementComparison.LINE_COUNTS);
    }

    @Test
    void the_accepted_counts_join_each_side_once_through_a_hash() throws SQLException {
        assertJoinedOnceThroughAHash(StatementComparison.ACCEPTED_COUNTS);
    }

    private void assertJoinedOnceThroughAHash(String query) throws SQLException {
        var period = lastEndedPeriod();
        var lines = new ArrayList<StatementLine>();
        for (var n = 0; n < TRANSFERS; n++) {
            var sentAt = period.start().plusMillis(n);
            var transferId = intent(sentAt);
            outcome(sentAt, transferId, JournalOutcome.ACKNOWLEDGED, TransferStatus.POSTED, null, sentAt);
            lines.add(new StatementLine(transferId, 1, 2, 100, TransferStatus.POSTED, null));
        }
        jdbcTemplate.execute("ANALYZE intents");
        jdbcTemplate.execute("ANALYZE outcomes");

        try (Connection connection = dataSource.getConnection();
             var comparison = new StatementComparison(connection, props.reconciliation().statementTimeout(), props.reconciliation().workMem())) {
            comparison.add(lines);
            var jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            jdbc.sql("ANALYZE statement_lines").update();
            var plan = plan(jdbc, query, period);

            assertThat(plan).doesNotContain("SubPlan", "Nested Loop", "Merge Join").contains("Hash");
            // On a thousand rows the planner picks a hash anyway; on a period it chose a probe per row.
            assertThat(setting(jdbc, "enable_nestloop")).isEqualTo("off");
            assertThat(setting(jdbc, "enable_mergejoin")).isEqualTo("off");
        }
    }

    private static String setting(JdbcClient jdbc, String name) {
        return jdbc.sql("SELECT current_setting(?)").param(name).query(String.class).single();
    }

    private String plan(JdbcClient jdbc, String query, Period period) {
        return jdbc.sql("EXPLAIN " + query)
            .params(StatementComparison.params(period, props.reconciliation().exampleLimit()))
            .query((rs, row) -> rs.getString(1))
            .list().stream().collect(Collectors.joining("\n"));
    }
}
