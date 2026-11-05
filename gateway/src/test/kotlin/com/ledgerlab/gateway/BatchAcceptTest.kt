package com.ledgerlab.gateway

import com.ledgerlab.gateway.service.IdempotencyConflictException
import com.ledgerlab.gateway.service.PeriodClosedException
import com.ledgerlab.gateway.service.PeriodService
import com.ledgerlab.gateway.service.TransferRequest
import com.ledgerlab.gateway.service.TransferService
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID
import java.util.concurrent.Executors

class BatchAcceptTest : IntegrationTest() {

    @Autowired lateinit var service: TransferService
    @Autowired lateinit var periods: PeriodService
    @Autowired lateinit var registry: MeterRegistry

    private fun request(runId: UUID, transferId: UUID = UUID.randomUUID(), amount: Long = 100) =
        TransferRequest(transferId, runId, 1, 2, amount)

    private fun counted(outcome: String) = registry.counter("gateway.transfers", "outcome", outcome).count()

    @Test
    fun `each request in a batch gets the outcome it would get alone, in its own place`() {
        val run = UUID.randomUUID()
        val closedRun = UUID.randomUUID()
        val stored = request(run)
        val storedInClosed = request(closedRun)
        service.acceptAll(listOf(stored, storedInClosed))
        periods.close(closedRun)
        val fresh = request(run)
        val sameKeyTwice = request(run)
        val sameKeyOtherAmount = sameKeyTwice.copy(amountCents = 999)
        val acceptedBefore = counted("accepted")
        val repeatedBefore = counted("repeated")

        val outcomes = service.acceptAll(
            listOf(
                fresh,
                stored,
                stored.copy(amountCents = 1),
                sameKeyTwice,
                sameKeyTwice,
                sameKeyOtherAmount,
                storedInClosed,
                request(closedRun),
            ),
        )

        assertThat(outcomes[0].getOrThrow().transferId).isEqualTo(fresh.transferId)
        assertThat(outcomes[1].getOrThrow().transferId).isEqualTo(stored.transferId)
        assertThat(outcomes[2].exceptionOrNull()).isInstanceOf(IdempotencyConflictException::class.java)
        assertThat(outcomes[3].getOrThrow().amountCents).isEqualTo(100)
        assertThat(outcomes[4].getOrThrow().transferId).isEqualTo(sameKeyTwice.transferId)
        assertThat(outcomes[5].exceptionOrNull()).isInstanceOf(IdempotencyConflictException::class.java)
        assertThat(outcomes[6].getOrThrow().transferId).isEqualTo(storedInClosed.transferId)
        assertThat(outcomes[7].exceptionOrNull()).isInstanceOf(PeriodClosedException::class.java)
        assertThat(counted("accepted")).isEqualTo(acceptedBefore + 2)
        assertThat(counted("repeated")).isEqualTo(repeatedBefore + 3)
    }

    @Test
    fun `a request of a closed run gets 422, not the row another run's request inserted under its key in the same batch`() {
        val openRun = UUID.randomUUID()
        val closedRun = UUID.randomUUID()
        service.acceptAll(listOf(request(closedRun)))
        periods.close(closedRun)
        val key = UUID.randomUUID()

        val outcomes = service.acceptAll(listOf(request(closedRun, key), request(openRun, key)))

        assertThat(outcomes[0].exceptionOrNull()).isInstanceOf(IdempotencyConflictException::class.java)
        assertThat(outcomes[1].getOrThrow().runId).isEqualTo(openRun)
    }

    @Test
    fun `concurrent submissions through the queue each get their own answer, and a repeat with another body is refused`() {
        val run = UUID.randomUUID()
        val ids = List(200) { UUID.randomUUID() }

        val statuses = Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            (ids + ids).map { id -> pool.submit<HttpStatus> { HttpStatus.valueOf(submit(body(id, run)).statusCode.value()) } }
                .map { it.get() }
        }

        assertThat(statuses).allMatch { it == HttpStatus.ACCEPTED }
        assertThat(service.summarize(run).accepted).isEqualTo(ids.size.toLong())
        assertThat(submit(body(ids.first(), run, amount = 1)).statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
    }
}
