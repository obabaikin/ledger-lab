package com.ledgerlab.generator.journal;

import com.ledgerlab.generator.GeneratorProperties;
import com.ledgerlab.generator.PlannedTransfer;
import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.TransferStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one writer of the client journal: rows gather for a moment and go in one transaction, intents and outcomes
 * together. A client waits for its intent to be committed; an outcome is handed over and not waited for.
 */
@Component
public class JournalWriter implements ClientJournal, SmartLifecycle {

    public static final String FAILURES_METRIC = "generator.journal.failures";
    /** Starts before the load and stops after it, so every outcome of the last requests is still written. */
    public static final int PHASE = 0;

    private static final String INSERT_INTENT =
        "INSERT INTO intents (sent_at, transfer_id, from_account, to_account, amount_cents) VALUES (?, ?, ?, ?, ?)";
    /**
     * The first outcome stands: one written again, or after the bank was asked, adds nothing. One statement over arrays,
     * not a batch: with reWriteBatchedInserts the driver does not tell which rows of a batch went in, and the totals
     * count only those.
     */
    static final String INSERT_OUTCOMES =
        """
        INSERT INTO outcomes (sent_at, transfer_id, outcome, bank_status, reject_reason, accepted_at)
        SELECT * FROM unnest(CAST(? AS timestamptz[]), CAST(? AS uuid[]), CAST(? AS text[]), CAST(? AS text[]), CAST(? AS text[]), CAST(? AS timestamptz[]))
        ON CONFLICT DO NOTHING
        RETURNING outcome
        """;

    private static final Logger log = LoggerFactory.getLogger(JournalWriter.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final GeneratorProperties.Journal props;
    private final Counter failures;
    private final LinkedBlockingQueue<Row> queue = new LinkedBlockingQueue<>();
    private volatile @Nullable Thread writer = null;
    private volatile boolean accepting = false;
    /** Read and written by the writer's thread only. */
    private long failedInARow = 0L;

    sealed interface Row {
    }

    private record Intent(Instant sentAt, PlannedTransfer transfer, CompletableFuture<@Nullable Void> written) implements Row {
    }

    public record Outcome(
        Instant sentAt,
        UUID transferId,
        JournalOutcome outcome,
        @Nullable TransferStatus bankStatus,
        @Nullable RejectReason rejectReason,
        @Nullable Instant acceptedAt
    ) implements Row {
    }

    private record Stop() implements Row {
    }

    public JournalWriter(JdbcTemplate jdbc, TransactionTemplate tx, GeneratorProperties props, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.props = props.journal();
        this.failures = Counter.builder(FAILURES_METRIC)
            .description("Batches the journal could not write: their transfers were not sent, their outcomes stay unknown until the bank is asked")
            .register(registry);
    }

    @Override
    public void intent(Instant sentAt, PlannedTransfer transfer) throws InterruptedException {
        if (!accepting) {
            throw new JournalUnavailableException("The journal is not writing", null);
        }
        var intent = new Intent(sentAt, transfer, new CompletableFuture<>());
        queue.add(intent);
        try {
            intent.written().get();
        } catch (ExecutionException e) {
            throw new JournalUnavailableException("The intent of transfer " + transfer.transferId() + " was not written", e.getCause());
        }
    }

    @Override
    public void outcome(
        Instant sentAt,
        PlannedTransfer transfer,
        JournalOutcome outcome,
        @Nullable TransferStatus bankStatus,
        @Nullable RejectReason rejectReason,
        @Nullable Instant acceptedAt
    ) {
        queue.add(new Outcome(sentAt, transfer.transferId(), outcome, bankStatus, rejectReason, acceptedAt));
    }

    /** Written at once, for the caller that asked the bank about unknown transfers and goes on only when they are stored. */
    public void writeNow(List<Outcome> outcomes) {
        tx.executeWithoutResult(status -> TotalsDelta.of(0, insertOutcomes(outcomes)).insert(jdbc));
    }

    private void write() {
        var batch = new ArrayList<Row>(props.batchSize());
        var stopped = false;
        while (!stopped) {
            try {
                batch.add(queue.take());
                if (queue.size() < props.batchSize()) {
                    Thread.sleep(props.flushInterval());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = true;
            }
            queue.drainTo(batch, props.batchSize() - batch.size());
            stopped |= batch.removeIf(Stop.class::isInstance);
            flush(batch);
            batch.clear();
        }
    }

    private void flush(List<Row> batch) {
        var intents = batch.stream().filter(Intent.class::isInstance).map(Intent.class::cast).toList();
        var outcomes = batch.stream().filter(Outcome.class::isInstance).map(Outcome.class::cast).toList();
        if (intents.isEmpty() && outcomes.isEmpty()) {
            return;
        }
        try {
            tx.executeWithoutResult(status -> {
                insertIntents(intents);
                TotalsDelta.of(intents.size(), insertOutcomes(outcomes)).insert(jdbc);
            });
            intents.forEach(intent -> intent.written().complete(null));
            if (failedInARow > 0) {
                log.info("The journal writes again after {} batches it could not write", failedInARow);
                failedInARow = 0;
            }
        } catch (RuntimeException e) {
            failures.increment();
            // While the database is away every batch fails the same way: the first is logged, the rest are counted.
            if (failedInARow++ == 0) {
                log.warn("The journal could not write {} intents and {} outcomes; their transfers are not sent", intents.size(), outcomes.size(), e);
            }
            intents.forEach(intent -> intent.written().completeExceptionally(e));
        }
    }

    private void insertIntents(List<Intent> intents) {
        jdbc.batchUpdate(INSERT_INTENT, intents, intents.size(), (statement, intent) -> {
            statement.setObject(1, Sql.timestamptz(intent.sentAt()));
            statement.setObject(2, intent.transfer().transferId());
            statement.setInt(3, intent.transfer().fromAccount());
            statement.setInt(4, intent.transfer().toAccount());
            statement.setLong(5, intent.transfer().amountCents());
        });
    }

    private List<JournalOutcome> insertOutcomes(List<Outcome> outcomes) {
        if (outcomes.isEmpty()) {
            return List.of();
        }
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(INSERT_OUTCOMES);
            statement.setArray(1, connection.createArrayOf("text", texts(outcomes, outcome -> outcome.sentAt().toString())));
            statement.setArray(2, connection.createArrayOf("text", texts(outcomes, outcome -> outcome.transferId().toString())));
            statement.setArray(3, connection.createArrayOf("text", texts(outcomes, outcome -> outcome.outcome().name())));
            statement.setArray(4, connection.createArrayOf("text", texts(outcomes, outcome -> Sql.nullableName(outcome.bankStatus()))));
            statement.setArray(5, connection.createArrayOf("text", texts(outcomes, outcome -> Sql.nullableName(outcome.rejectReason()))));
            statement.setArray(6, connection.createArrayOf("text", texts(outcomes, outcome -> Sql.nullableText(outcome.acceptedAt()))));
            return statement;
        }, (rs, row) -> JournalOutcome.valueOf(rs.getString("outcome")));
    }

    private static @Nullable String[] texts(List<Outcome> outcomes, Function<Outcome, @Nullable String> column) {
        return outcomes.stream().map(column).toArray(String[]::new);
    }

    @Override
    public synchronized void start() {
        if (writer != null) {
            return;
        }
        accepting = true;
        writer = Thread.ofVirtual().name("journal-writer").start(this::write);
    }

    /** Everything handed over before the stop is written first. */
    @Override
    public synchronized void stop() {
        var writing = writer;
        if (writing == null) {
            return;
        }
        accepting = false;
        queue.add(new Stop());
        try {
            writing.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        writer = null;
        // A client that slipped in beside the stop must not wait for a writer that is gone.
        var notWritten = new JournalUnavailableException("The journal has stopped", null);
        for (var row = queue.poll(); row != null; row = queue.poll()) {
            if (row instanceof Intent intent) {
                intent.written().completeExceptionally(notWritten);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return writer != null;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
