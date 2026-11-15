package com.ledgerlab.generator.journal;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.generator.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SocketTimeoutTest extends IntegrationTest {

    @Autowired DataSource dataSource;

    @Test
    void the_generators_connections_give_up_on_a_frozen_database_but_only_after_the_reconciliations_longest_statement() throws SQLException {
        var setting = dataSource.unwrap(HikariDataSource.class).getDataSourceProperties().getProperty("socketTimeout");

        assertThat(setting).isNotNull();
        assertThat(Duration.ofSeconds(Long.parseLong(setting))).isGreaterThan(props.reconciliation().statementTimeout());
    }
}
