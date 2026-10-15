package com.ledgerlab.control.chaos

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

data class ChaosEvent(val at: Instant, val instances: List<String>)

@Repository
class ChaosRepository(private val jdbc: JdbcClient) {

    fun lock() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))")
            .param("name", CHAOS_LOCK)
            .query { _, _ -> }
            .single()
    }

    fun lastAt(): Instant? =
        jdbc.sql("SELECT at FROM chaos_events ORDER BY at DESC LIMIT 1")
            .query(Timestamp::class.java)
            .optional()
            .map { it.toInstant() }
            .orElse(null)

    fun insert(event: ChaosEvent) {
        jdbc.sql("INSERT INTO chaos_events (at, instances) VALUES (:at, :instances)")
            .param("at", Timestamp.from(event.at))
            .param("instances", event.instances.toTypedArray())
            .update()
    }

    fun since(from: Instant): List<ChaosEvent> =
        jdbc.sql("SELECT at, instances FROM chaos_events WHERE at >= :from ORDER BY at")
            .param("from", Timestamp.from(from))
            .query { rs, _ -> rs.toEvent() }
            .list()

    private fun ResultSet.toEvent() = ChaosEvent(
        at = getTimestamp("at").toInstant(),
        instances = (getArray("instances").array as Array<*>).map { it as String },
    )

    private companion object {
        const val CHAOS_LOCK = "control.chaos"
    }
}
