package com.ledgerlab.gateway

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

class StatementTest : IntegrationTest() {

    @Autowired lateinit var dataSource: DataSource

    private fun close(runId: UUID): ResponseEntity<Map<*, *>> =
        http.postForEntity("/internal/runs/$runId/close", null, Map::class.java)

    private fun statement(runId: UUID, query: String = ""): ResponseEntity<Map<*, *>> =
        http.getForEntity("/v1/runs/$runId/statement$query", Map::class.java)

    private fun allItems(runId: UUID, limit: Int): List<List<Map<String, Any?>>> {
        val pages = mutableListOf<List<Map<String, Any?>>>()
        var after: Any? = null
        do {
            val page = statement(runId, "?limit=$limit" + (after?.let { "&after=$it" } ?: "")).body!!
            @Suppress("UNCHECKED_CAST")
            pages += page["items"] as List<Map<String, Any?>>
            after = page["next"]
        } while (after != null)
        return pages
    }

    @Test
    fun `the statement of a closed period lists every transfer in acceptance order, page by page`() {
        val runId = UUID.randomUUID()
        val sent = List(5) { i -> body(runId = runId, from = 1, to = 2 + i, amount = 100L + i).also { submit(it) } }

        val closed = close(runId)
        val pages = allItems(runId, limit = 2)

        assertThat(closed.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(pages.map { it.size }).containsExactly(2, 2, 1)
        val items = pages.flatten()
        assertThat(items.map { it["transferId"] }).containsExactlyInAnyOrderElementsOf(sent.map { it["transferId"].toString() })
        assertThat(items.map { it["acceptedAt"] as String }).isSorted()
        val first = sent.first { it["transferId"].toString() == items[0]["transferId"] }
        assertThat(items[0]).containsEntry("fromAccount", 1).containsEntry("toAccount", first["toAccount"])
            .containsEntry("amountCents", (first["amountCents"] as Long).toInt())
            .containsKeys("status", "rejectReason", "acceptedAt", "resultedAt").doesNotContainKey("publishedAt")
        assertThat(statement(runId).body!!["closedAt"]).isEqualTo(closed.body!!["closedAt"])
    }

    @Test
    fun `there is no statement before the period is closed`() {
        val runId = UUID.randomUUID()
        submit(body(runId = runId))

        val open = statement(runId)
        val unknown = statement(UUID.randomUUID())

        assertThat(open.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(open.body!!["error"]).isEqualTo("period not closed")
        assertThat(unknown.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `closing twice keeps the first closing time and an unknown run gets an empty statement`() {
        val runId = UUID.randomUUID()

        val first = close(runId)
        val second = close(runId)

        assertThat(second.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(second.body!!["closedAt"]).isEqualTo(first.body!!["closedAt"])
        val empty = statement(runId).body!!
        assertThat(empty["items"] as List<*>).isEmpty()
        assertThat(empty["next"]).isNull()
    }

    @Test
    fun `after closing a new key is refused with 409 while a known key is answered as before`() {
        val runId = UUID.randomUUID()
        val known = body(runId = runId, amount = 100)
        submit(known)
        close(runId)

        val repeated = submit(known)
        val otherPayload = submit(known + ("amountCents" to 999))
        val newKey = submit(body(runId = runId))

        assertThat(repeated.statusCode.is2xxSuccessful).isTrue()
        assertThat(otherPayload.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(newKey.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(newKey.body!!["error"]).isEqualTo("period closed")
        assertThat(allItems(runId, 100).flatten().map { it["transferId"] }).containsExactly(known["transferId"].toString())
    }

    @Test
    fun `submissions racing a close are in the statement exactly when they were accepted`() {
        Executors.newFixedThreadPool(9).use { pool ->
            repeat(10) {
                val runId = UUID.randomUUID()
                val gate = CountDownLatch(1)
                val requests = List(40) { body(runId = runId) }
                val answers = requests.chunked(5).map { chunk ->
                    pool.submit<List<Pair<Map<String, Any>, HttpStatus>>> {
                        gate.await()
                        chunk.map { it to HttpStatus.valueOf(submit(it).statusCode.value()) }
                    }
                }
                val closing = pool.submit<ResponseEntity<Map<*, *>>> { gate.await(); Thread.sleep(5); close(runId) }
                gate.countDown()

                val outcomes = answers.flatMap { it.get() }
                assertThat(closing.get().statusCode).isEqualTo(HttpStatus.OK)
                assertThat(outcomes.map { it.second }).allMatch { it == HttpStatus.ACCEPTED || it == HttpStatus.OK || it == HttpStatus.CONFLICT }
                val acceptedIds = outcomes.filter { it.second != HttpStatus.CONFLICT }.map { it.first["transferId"].toString() }
                assertThat(allItems(runId, 7).flatten().map { it["transferId"] }).containsExactlyInAnyOrderElementsOf(acceptedIds)
            }
        }
    }

    @Test
    fun `close waits for an insert in flight, so that insert is in the statement`() {
        val runId = UUID.randomUUID()
        val transferId = UUID.randomUUID()
        dataSource.connection.use { insert ->
            insert.autoCommit = false
            insert.holdRunLockShared(runId)
            insert.prepareStatement(
                "INSERT INTO transfers (transfer_id, run_id, from_account, to_account, amount_cents, status) VALUES (?, ?, 1, 2, 100, 'ACCEPTED')",
            ).use { it.setObject(1, transferId); it.setObject(2, runId); it.executeUpdate() }

            val closing = CompletableFuture.supplyAsync { close(runId) }
            Thread.sleep(300)
            assertThat(closing).isNotDone()
            insert.commit()

            assertThat(closing.get().statusCode).isEqualTo(HttpStatus.OK)
        }
        val statement = statement(runId).body!!
        @Suppress("UNCHECKED_CAST")
        val items = statement["items"] as List<Map<String, Any?>>
        assertThat(items.map { it["transferId"] }).containsExactly(transferId.toString())
        assertThat(Instant.parse(items[0]["acceptedAt"] as String)).isBefore(Instant.parse(statement["closedAt"] as String))
    }

    @Test
    fun `close gives up with 503 when an insert in flight outlasts the lock timeout`() {
        val runId = UUID.randomUUID()
        dataSource.connection.use { insert ->
            insert.autoCommit = false
            insert.holdRunLockShared(runId)

            val timedOut = close(runId)

            assertThat(timedOut.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(statement(runId).statusCode).isEqualTo(HttpStatus.CONFLICT)
            insert.rollback()
        }
        assertThat(close(runId).statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `an invalid limit or a cursor from another run is a bad request`() {
        val runId = UUID.randomUUID()
        val otherRun = UUID.randomUUID()
        val foreign = body(runId = otherRun).also { submit(it) }["transferId"]
        close(runId)

        assertThat(statement(runId, "?limit=0").statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(statement(runId, "?limit=1001").statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(statement(runId, "?after=$foreign").statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(statement(runId, "?limit=1000").statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `deleting a run removes its period too`() {
        val runId = UUID.randomUUID()
        submit(body(runId = runId))
        close(runId)

        http.delete("/internal/runs/$runId")

        assertThat(statement(runId).statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(submit(body(runId = runId)).statusCode).isEqualTo(HttpStatus.ACCEPTED)
    }

    private fun java.sql.Connection.holdRunLockShared(runId: UUID) {
        prepareStatement("SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))").use {
            it.setString(1, runId.toString())
            it.executeQuery().close()
        }
    }
}
