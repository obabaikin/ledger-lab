package com.ledgerlab.gateway

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

class MigrationTest : IntegrationTest() {

    @Autowired lateinit var dataSource: DataSource

    @Test
    fun `a period closed before entry counts existed gets the count of its transfers`() {
        val schema = "migration_" + UUID.randomUUID().toString().replace("-", "")
        val flyway = { target: String ->
            Flyway.configure().dataSource(dataSource).schemas(schema).target(target).load()
        }
        val closedRun = UUID.randomUUID()
        val emptyRun = UUID.randomUUID()
        flyway("2").migrate()
        dataSource.connection.use { c ->
            repeat(3) {
                c.prepareStatement(
                    "INSERT INTO $schema.transfers (transfer_id, run_id, from_account, to_account, amount_cents, status) VALUES (?, ?, 1, 2, 100, 'ACCEPTED')",
                ).use { st -> st.setObject(1, UUID.randomUUID()); st.setObject(2, closedRun); st.executeUpdate() }
            }
            for (runId in listOf(closedRun, emptyRun)) {
                c.prepareStatement("INSERT INTO $schema.run_periods (run_id, closed_at) VALUES (?, now())")
                    .use { st -> st.setObject(1, runId); st.executeUpdate() }
            }
        }

        flyway("latest").migrate()

        val counts = dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT run_id, entry_count FROM $schema.run_periods").use { rs ->
                    buildMap { while (rs.next()) put(rs.getObject(1, UUID::class.java), rs.getLong(2)) }
                }
            }
        }
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(mapOf(closedRun to 3L, emptyRun to 0L))
        assertThatThrownBy {
            dataSource.connection.use { c ->
                c.createStatement().use { it.execute("INSERT INTO $schema.run_periods (run_id, closed_at) VALUES ('${UUID.randomUUID()}', now())") }
            }
        }.isInstanceOf(SQLException::class.java)
        dataSource.connection.use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
    }
}
