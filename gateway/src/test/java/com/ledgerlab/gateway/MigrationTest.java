package com.ledgerlab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MigrationTest extends IntegrationTest {

    @Autowired DataSource dataSource;

    @Test
    void a_period_closed_before_entry_counts_existed_gets_the_count_of_its_transfers() throws SQLException {
        var schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        var closedRun = UUID.randomUUID();
        var emptyRun = UUID.randomUUID();

        migrate(schema, "2");
        try (var c = dataSource.getConnection()) {
            for (var i = 0; i < 3; i++) {
                try (var st = c.prepareStatement(
                    "INSERT INTO " + schema + ".transfers (transfer_id, run_id, from_account, to_account, amount_cents, status) VALUES (?, ?, 1, 2, 100, 'ACCEPTED')")) {
                    st.setObject(1, UUID.randomUUID());
                    st.setObject(2, closedRun);
                    st.executeUpdate();
                }
            }
            for (var runId : List.of(closedRun, emptyRun)) {
                try (var st = c.prepareStatement("INSERT INTO " + schema + ".run_periods (run_id, closed_at) VALUES (?, now())")) {
                    st.setObject(1, runId);
                    st.executeUpdate();
                }
            }
        }
        migrate(schema, "latest");

        var counts = new HashMap<UUID, Long>();
        try (var c = dataSource.getConnection();
            var st = c.createStatement();
            var rs = st.executeQuery("SELECT run_id, entry_count FROM " + schema + ".run_periods")) {
            while (rs.next()) {
                counts.put(rs.getObject(1, UUID.class), rs.getLong(2));
            }
        }
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(closedRun, 3L, emptyRun, 0L));
        assertThatThrownBy(() -> execute("INSERT INTO " + schema + ".run_periods (run_id, closed_at) VALUES ('" + UUID.randomUUID() + "', now())"))
            .isInstanceOf(SQLException.class);
        execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private void migrate(String schema, String target) {
        Flyway.configure().dataSource(dataSource).schemas(schema).target(target).load().migrate();
    }

    private void execute(String sql) throws SQLException {
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
