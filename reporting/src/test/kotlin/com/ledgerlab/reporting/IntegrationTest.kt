package com.ledgerlab.reporting

import com.ledgerlab.common.RejectReason
import com.ledgerlab.common.TransferCommand
import com.ledgerlab.common.TransferOutcome
import com.ledgerlab.common.TransferResultEvent
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
abstract class IntegrationTest {

    protected fun command(runId: UUID, amountCents: Long, acceptedAt: Instant, transferId: UUID = UUID.randomUUID()) =
        TransferCommand(runId, transferId, fromAccount = 1, toAccount = 2, amountCents, acceptedAt)

    protected fun posted(command: TransferCommand, processedAt: Instant) =
        TransferResultEvent(command.runId, command.transferId, TransferOutcome.POSTED, null, processedAt)

    protected fun rejected(command: TransferCommand, reason: RejectReason, processedAt: Instant) =
        TransferResultEvent(command.runId, command.transferId, TransferOutcome.REJECTED, reason, processedAt)
}
