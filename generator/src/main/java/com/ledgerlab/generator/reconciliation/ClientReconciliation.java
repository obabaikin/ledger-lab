package com.ledgerlab.generator.reconciliation;

import com.ledgerlab.generator.GeneratorProperties;
import com.ledgerlab.generator.gateway.BadGatewayAnswerException;
import com.ledgerlab.generator.gateway.GatewayClient;
import com.ledgerlab.generator.journal.JournalOutcome;
import com.ledgerlab.generator.journal.JournalRepository;
import com.ledgerlab.generator.journal.JournalRepository.IntentKey;
import com.ledgerlab.generator.journal.JournalWriter;
import com.ledgerlab.generator.journal.Period;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * The client journal against the bank's statement of one closed period (ADR 0010). First the bank is asked about the
 * transfers sent in the period before whose outcome nobody learned: one of them may have been accepted in this period.
 */
@Component
public class ClientReconciliation {

    private static final int UNKNOWN_PAGE = 1_000;

    private final GatewayClient gateway;
    private final JournalRepository journal;
    private final JournalWriter writer;
    private final DataSource dataSource;
    private final GeneratorProperties.Reconciliation props;

    public ClientReconciliation(GatewayClient gateway, JournalRepository journal, JournalWriter writer, DataSource dataSource, GeneratorProperties props) {
        this.gateway = gateway;
        this.journal = journal;
        this.writer = writer;
        this.dataSource = dataSource;
        this.props = props.reconciliation();
    }

    public ClientReconciliationReport reconcile(Period period) throws InterruptedException {
        resolveUnknown(period.previous());
        try (var connection = dataSource.getConnection();
             var comparison = new StatementComparison(connection, props.statementTimeout())) {
            var lines = readStatement(period, comparison);
            var report = comparison.report(period, journal.resolved(period.previous()), props.exampleLimit());
            if (report.statement().rows() != lines) {
                throw new IllegalStateException("The statement was stored with " + report.statement().rows() + " lines of " + lines);
            }
            return report;
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("No connection for the reconciliation of the period from " + period.start(), e);
        }
    }

    private long readStatement(Period period, StatementComparison comparison) {
        var what = "The statement of the period from " + period.start();
        var cursors = new HashSet<String>();
        Long entryCount = null;
        var lines = 0L;
        String after = null;
        do {
            var page = gateway.statement(period.start(), after);
            if (entryCount != null && page.entryCount() != entryCount) {
                throw new BadGatewayAnswerException(what + " changed its totals between pages: entryCount " + entryCount + ", then " + page.entryCount());
            }
            entryCount = page.entryCount();
            try {
                comparison.add(page.items());
            } catch (DuplicateKeyException e) {
                throw new BadGatewayAnswerException(what + " lists a transfer twice");
            }
            lines += page.items().size();
            after = page.next();
            if (after != null && !cursors.add(after)) {
                throw new BadGatewayAnswerException(what + " pages back to " + after);
            }
        } while (after != null);
        if (lines != entryCount) {
            throw new BadGatewayAnswerException(what + " is incomplete: " + lines + " lines, entryCount " + entryCount);
        }
        return lines;
    }

    private void resolveUnknown(Period sentIn) throws InterruptedException {
        var asking = new Semaphore(props.resolveConcurrency());
        var after = IntentKey.before(sentIn);
        try (var askers = Executors.newVirtualThreadPerTaskExecutor()) {
            while (true) {
                var page = journal.unknown(sentIn, after, UNKNOWN_PAGE);
                if (page.isEmpty()) {
                    return;
                }
                var answers = new ArrayList<Future<JournalWriter.Outcome>>();
                for (var intent : page) {
                    answers.add(askers.submit(() -> {
                        asking.acquire();
                        try {
                            return ask(intent);
                        } finally {
                            asking.release();
                        }
                    }));
                }
                var outcomes = new ArrayList<JournalWriter.Outcome>();
                for (var answer : answers) {
                    outcomes.add(get(answer));
                }
                writer.writeNow(outcomes);
                after = page.getLast();
            }
        }
    }

    private JournalWriter.Outcome ask(IntentKey intent) {
        var transfer = gateway.transfer(intent.transferId());
        if (transfer == null) {
            return new JournalWriter.Outcome(intent.sentAt(), intent.transferId(), JournalOutcome.UNKNOWN_NOT_ACCEPTED, null, null, null);
        }
        return new JournalWriter.Outcome(
            intent.sentAt(), intent.transferId(), JournalOutcome.UNKNOWN_ACCEPTED, transfer.status(), transfer.rejectReason(), transfer.acceptedAt());
    }

    private static JournalWriter.Outcome get(Future<JournalWriter.Outcome> answer) throws InterruptedException {
        try {
            return answer.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw new IllegalStateException(e.getCause());
        }
    }
}
