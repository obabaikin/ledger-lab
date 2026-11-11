package com.ledgerlab.reporting;

import com.ledgerlab.common.RejectReason;
import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.common.TransferResultEvent;
import com.ledgerlab.reporting.domain.Period;
import com.ledgerlab.reporting.persistence.AcceptedTransfer;
import com.ledgerlab.reporting.persistence.SecondStatsRepository;
import com.ledgerlab.reporting.persistence.TransferKey;
import com.ledgerlab.reporting.persistence.TransferOutcomeRow;
import com.ledgerlab.reporting.persistence.TransferStatsRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/** One bank for all tests of a context: each test starts with no transfers and no numbers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
abstract class IntegrationTest {

    @Autowired TransferStatsRepository transfers;
    @Autowired SecondStatsRepository seconds;
    @Autowired ReportingProperties props;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void emptyBank() {
        jdbcTemplate.update("DELETE FROM transfer_stats");
        jdbcTemplate.update("DELETE FROM second_stats");
        jdbcTemplate.update("DELETE FROM second_rejections");
    }

    protected Period currentPeriod() {
        return Period.containing(Instant.now(), props.periods().length());
    }

    protected Period lastEndedPeriod() {
        return currentPeriod().previous();
    }

    protected TransferCommand command(long amountCents, Instant acceptedAt) {
        return new TransferCommand(UUID.randomUUID(), 1, 2, amountCents, acceptedAt);
    }

    protected TransferResultEvent posted(TransferCommand command, Instant processedAt) {
        return new TransferResultEvent(command.transferId(), TransferOutcome.POSTED, null, processedAt, command.acceptedAt());
    }

    protected TransferResultEvent rejected(TransferCommand command, RejectReason reason, Instant processedAt) {
        return new TransferResultEvent(command.transferId(), TransferOutcome.REJECTED, reason, processedAt, command.acceptedAt());
    }

    protected static AcceptedTransfer row(TransferCommand command) {
        return new AcceptedTransfer(new TransferKey(command.acceptedAt(), command.transferId()), command.amountCents());
    }

    protected static TransferOutcomeRow row(TransferResultEvent result) {
        return new TransferOutcomeRow(
            new TransferKey(result.acceptedAt(), result.transferId()),
            result.outcome().name(),
            result.rejectReason() == null ? null : result.rejectReason().name(),
            result.processedAt());
    }

    /** As the consumer stores a batch of commands: the rows and the numbers in one transaction. */
    protected void store(TransferCommand... commands) {
        var rows = Arrays.stream(commands).map(IntegrationTest::row).toList();
        tx.executeWithoutResult(status -> seconds.add(transfers.writeAccepted(rows)));
    }

    protected void store(TransferResultEvent... results) {
        var rows = Arrays.stream(results).map(IntegrationTest::row).toList();
        tx.executeWithoutResult(status -> seconds.add(transfers.writeOutcomes(rows)));
    }
}
