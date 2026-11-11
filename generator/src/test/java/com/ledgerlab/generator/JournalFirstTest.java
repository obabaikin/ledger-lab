package com.ledgerlab.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Its own database, since the test freezes it: the journal's writer then waits for an answer that does not come. */
class JournalFirstTest {

    private static final String SCHEMA = "generator";

    private static HikariDataSource dataSource(PostgreSQLContainer postgres) {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setSchema(SCHEMA);
        return new HikariDataSource(config);
    }

    @Test
    @DisplayName("while the journal's database is away the generator sends nothing, and sends again once it is back")
    void while_the_journals_database_is_away_the_generator_sends_nothing_and_sends_again_once_it_is_back() throws Exception {
        try (var postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE); var gateway = new FakeGateway()) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).defaultSchema(SCHEMA).load().migrate();
            try (var dataSource = dataSource(postgres)) {
                var settings = TestData.settings(gateway.url()).tps(200).build();
                var registry = new SimpleMeterRegistry();
                var clock = Clock.systemUTC();
                var writer = new JournalWriter(
                    new JdbcTemplate(dataSource), new TransactionTemplate(new DataSourceTransactionManager(dataSource)), settings, registry);
                var load = new Load(settings, new GatewayClient(settings, new ObjectMapper(), registry), writer, new ResponseMinutes(clock, settings), clock, registry);
                writer.start();
                var paused = false;
                try {
                    postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();
                    paused = true;
                    load.begin();
                    Thread.sleep(2_000);

                    assertThat(gateway.requests.get()).isZero();
                    assertThat(load.status().requested()).isZero();

                    postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec();
                    paused = false;
                    await().atMost(Duration.ofSeconds(15)).until(() -> gateway.requests.get() > 50);
                } finally {
                    // A failed assertion must not leave the database frozen: the writer's stop waits for its statement to end.
                    if (paused) {
                        postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec();
                    }
                    load.stop();
                    writer.stop();
                }
                var status = load.status();
                assertThat(status.acknowledged()).isEqualTo(status.requested());
                assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT count(*) FROM intents", Long.class)).isGreaterThanOrEqualTo(status.requested());
            }
        }
    }
}
