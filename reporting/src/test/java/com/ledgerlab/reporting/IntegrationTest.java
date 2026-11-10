package com.ledgerlab.reporting;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.common.TransferResultEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
abstract class IntegrationTest {

    protected TransferCommand command(UUID runId, long amountCents, Instant acceptedAt) {
        return new TransferCommand(runId, UUID.randomUUID(), 1, 2, amountCents, acceptedAt);
    }

    protected TransferResultEvent posted(TransferCommand command, Instant processedAt) {
        return new TransferResultEvent(command.runId(), command.transferId(), TransferOutcome.POSTED, null, processedAt);
    }

    protected TransferResultEvent rejected(TransferCommand command, RejectReason reason, Instant processedAt) {
        return new TransferResultEvent(command.runId(), command.transferId(), TransferOutcome.REJECTED, reason, processedAt);
    }
}
