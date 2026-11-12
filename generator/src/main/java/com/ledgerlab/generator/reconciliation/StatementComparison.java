package com.ledgerlab.generator.reconciliation;

import com.ledgerlab.generator.bank.RejectReason;
import com.ledgerlab.generator.bank.StatementLine;
import com.ledgerlab.generator.bank.TransferStatus;
import com.ledgerlab.generator.journal.JournalOutcome;
import com.ledgerlab.generator.journal.Period;
import com.ledgerlab.generator.journal.Sql;
import com.ledgerlab.generator.journal.UnknownResolved;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * A period's statement against the client journal, inside the database: the statement goes page by page into a
 * temporary table and is joined with the journal there, so a period of over a million transfers is never held in the
 * generator's memory. Everything runs on one connection, which is where a temporary table lives, and outside a
 * transaction: one open for minutes would hold back vacuum for the bank, which shares the database.
 */
final class StatementComparison implements AutoCloseable {

    private static final String LINES = "statement_lines";

    /** The journal of the transfers that can be in the statement of a period: sent in it or in the one before. */
    private static final String JOURNAL =
        """
        SELECT i.transfer_id, i.from_account, i.to_account, i.amount_cents, o.outcome, o.bank_status, o.reject_reason
        FROM intents i
        LEFT JOIN outcomes o ON o.sent_at = i.sent_at AND o.transfer_id = i.transfer_id AND o.sent_at >= :from AND o.sent_at < :end
        WHERE i.sent_at >= :from AND i.sent_at < :end
        """;
    private static final String LINES_WITH_JOURNAL = LINES + " s LEFT JOIN (" + JOURNAL + ") j ON j.transfer_id = s.transfer_id";

    private static final String NOT_IN_JOURNAL = "j.transfer_id IS NULL";
    private static final String NOT_TAKEN_BUT_IN_STATEMENT = "j.outcome IN (:notTaken)";
    private static final String PAYLOAD_MISMATCH =
        "j.transfer_id IS NOT NULL AND (j.from_account <> s.from_account OR j.to_account <> s.to_account OR j.amount_cents <> s.amount_cents)";
    /** A status or reason this client could not read, on either side, is not compared (ADR 0010). */
    private static final String STATUS_MISMATCH =
        """
        j.outcome = :acknowledged AND j.bank_status IN (:finalStatuses) AND s.status IS NOT NULL
        AND (j.bank_status <> s.status OR (j.reject_reason IS NOT NULL AND s.reject_reason IS NOT NULL AND j.reject_reason <> s.reject_reason))
        """;
    private static final String ACCEPTED_IN_PERIOD =
        """
        outcomes o
        WHERE o.sent_at >= :from AND o.sent_at < :end AND o.accepted_at >= :start AND o.accepted_at < :end AND o.outcome IN (:accepted)
        """;
    private static final String NOT_IN_STATEMENT = "NOT EXISTS (SELECT 1 FROM " + LINES + " s WHERE s.transfer_id = o.transfer_id)";

    private final JdbcTemplate template;
    private final JdbcClient jdbc;

    StatementComparison(Connection connection, Duration statementTimeout) {
        var onThisConnection = new SingleConnectionDataSource(connection, true);
        this.template = new JdbcTemplate(onThisConnection);
        this.jdbc = JdbcClient.create(onThisConnection);
        template.execute("SET statement_timeout = '" + statementTimeout.toMillis() + "ms'");
        template.execute("DROP TABLE IF EXISTS " + LINES);
        template.execute(
            """
            CREATE TEMP TABLE statement_lines (
                transfer_id   uuid    PRIMARY KEY,
                from_account  integer NOT NULL,
                to_account    integer NOT NULL,
                amount_cents  bigint  NOT NULL,
                status        text,
                reject_reason text
            )
            """);
    }

    /** Fails with DuplicateKeyException for a transfer the statement has already listed. */
    void add(List<StatementLine> lines) {
        template.batchUpdate(
            "INSERT INTO " + LINES + " (transfer_id, from_account, to_account, amount_cents, status, reject_reason) VALUES (?, ?, ?, ?, ?, ?)",
            lines, lines.size(), (statement, line) -> {
                statement.setObject(1, line.transferId());
                statement.setInt(2, line.fromAccount());
                statement.setInt(3, line.toAccount());
                statement.setLong(4, line.amountCents());
                statement.setString(5, Sql.nullableName(line.status()));
                statement.setString(6, Sql.nullableName(line.rejectReason()));
            });
    }

    ClientReconciliationReport report(Period period, UnknownResolved unknownResolved, int exampleLimit) {
        // Autovacuum does not see a temporary table: without this the joins are planned for an empty one.
        template.execute("ANALYZE " + LINES);
        var params = new HashMap<String, Object>();
        params.put("from", Sql.timestamptz(period.previous().start()));
        params.put("start", Sql.timestamptz(period.start()));
        params.put("end", Sql.timestamptz(period.end()));
        params.put("limit", exampleLimit);
        params.put("acknowledged", JournalOutcome.ACKNOWLEDGED.name());
        params.put("accepted", List.of(JournalOutcome.ACKNOWLEDGED.name(), JournalOutcome.UNKNOWN_ACCEPTED.name()));
        params.put("unknownAccepted", JournalOutcome.UNKNOWN_ACCEPTED.name());
        params.put("notTaken", List.of(JournalOutcome.FAILED.name(), JournalOutcome.UNKNOWN_NOT_ACCEPTED.name()));
        params.put("finalStatuses", Arrays.stream(TransferStatus.values()).filter(TransferStatus::isFinal).map(Enum::name).toList());

        var lines = jdbc.sql(
            """
            SELECT count(*) AS line_count,
                   coalesce(sum(s.amount_cents), 0) AS amount_cents,
                   count(*) FILTER (WHERE s.status IS NULL OR s.status NOT IN (:finalStatuses)) AS not_final,
                   count(*) FILTER (WHERE j.transfer_id IS NOT NULL AND (j.outcome IS NULL OR j.outcome = :unknownAccepted)) AS unknown_to_client,
                   count(*) FILTER (WHERE %s) AS not_in_journal,
                   count(*) FILTER (WHERE %s) AS not_taken_but_in_statement,
                   count(*) FILTER (WHERE %s) AS payload_mismatch,
                   count(*) FILTER (WHERE %s) AS status_mismatch
            FROM %s
            """.formatted(NOT_IN_JOURNAL, NOT_TAKEN_BUT_IN_STATEMENT, PAYLOAD_MISMATCH, STATUS_MISMATCH, LINES_WITH_JOURNAL))
            .params(params)
            .query((rs, row) -> new LineCounts(
                rs.getLong("line_count"), rs.getLong("amount_cents"), rs.getLong("not_final"), rs.getLong("unknown_to_client"),
                rs.getLong("not_in_journal"), rs.getLong("not_taken_but_in_statement"), rs.getLong("payload_mismatch"), rs.getLong("status_mismatch")))
            .single();
        var accepted = jdbc.sql(
            "SELECT count(*) AS accepted, count(*) FILTER (WHERE %s) AS not_in_statement FROM %s".formatted(NOT_IN_STATEMENT, ACCEPTED_IN_PERIOD))
            .params(params)
            .query((rs, row) -> new AcceptedCounts(rs.getLong("accepted"), rs.getLong("not_in_statement")))
            .single();

        var breaks = new ReconciliationBreaks(
            ids(lines.notInJournal(), firstLinesWithJournal("s.transfer_id", NOT_IN_JOURNAL), params),
            ids(accepted.notInStatement(), "SELECT o.transfer_id FROM %s AND %s ORDER BY o.transfer_id LIMIT :limit".formatted(ACCEPTED_IN_PERIOD, NOT_IN_STATEMENT), params),
            ids(lines.notTakenButInStatement(), firstLinesWithJournal("s.transfer_id", NOT_TAKEN_BUT_IN_STATEMENT), params),
            sides(lines.payloadMismatch(), PAYLOAD_MISMATCH, params),
            sides(lines.statusMismatch(), STATUS_MISMATCH, params));
        return new ClientReconciliationReport(
            period.start(),
            breaks.none(),
            lines.notFinal() == 0,
            accepted.accepted(),
            new StatementTotals(lines.rows(), lines.amountCents(), byStatus(), lines.unknownToClient()),
            breaks,
            unknownResolved);
    }

    private record LineCounts(
        long rows, long amountCents, long notFinal, long unknownToClient,
        long notInJournal, long notTakenButInStatement, long payloadMismatch, long statusMismatch
    ) {
    }

    private record AcceptedCounts(long accepted, long notInStatement) {
    }

    private Map<TransferStatus, Long> byStatus() {
        var byStatus = new EnumMap<TransferStatus, Long>(TransferStatus.class);
        jdbc.sql("SELECT status, count(*) AS line_count FROM " + LINES + " WHERE status IS NOT NULL GROUP BY status")
            .query((ResultSet rs) -> {
                byStatus.put(TransferStatus.valueOf(rs.getString("status")), rs.getLong("line_count"));
            });
        return byStatus;
    }

    /** The examples are read only for a break that exists: each is another pass over the period. */
    private TransferIds ids(long count, String sql, Map<String, Object> params) {
        if (count == 0) {
            return TransferIds.NONE;
        }
        return new TransferIds(count, jdbc.sql(sql).params(params).query((rs, row) -> rs.getObject("transfer_id", UUID.class)).list());
    }

    private TransferSides sides(long count, String predicate, Map<String, Object> params) {
        if (count == 0) {
            return TransferSides.NONE;
        }
        var examples = jdbc.sql(firstLinesWithJournal(
                """
                s.transfer_id,
                j.from_account AS client_from, j.to_account AS client_to, j.amount_cents AS client_amount,
                j.bank_status AS client_status, j.reject_reason AS client_reason,
                s.from_account AS bank_from, s.to_account AS bank_to, s.amount_cents AS bank_amount,
                s.status AS bank_status, s.reject_reason AS bank_reason
                """,
                predicate))
            .params(params)
            .query((rs, row) -> new TransferSides.MismatchedTransfer(rs.getObject("transfer_id", UUID.class), side(rs, "client"), side(rs, "bank")))
            .list();
        return new TransferSides(count, examples);
    }

    /**
     * Joined in full before the limit: under a limit the planner expects the first rows early and probes the journal once
     * per statement line by transfer_id, which its index does not lead with; on statistics that miss the period's rows,
     * that read the period once per line.
     */
    private static String firstLinesWithJournal(String columns, String predicate) {
        return """
            WITH matching AS MATERIALIZED (SELECT %s FROM %s WHERE %s)
            SELECT * FROM matching ORDER BY transfer_id LIMIT :limit
            """.formatted(columns, LINES_WITH_JOURNAL, predicate);
    }

    private static TransferSides.TransferSide side(ResultSet rs, String prefix) throws SQLException {
        return new TransferSides.TransferSide(
            rs.getInt(prefix + "_from"), rs.getInt(prefix + "_to"), rs.getLong(prefix + "_amount"),
            enumOrNull(TransferStatus.class, rs.getString(prefix + "_status")),
            enumOrNull(RejectReason.class, rs.getString(prefix + "_reason")));
    }

    private static <E extends Enum<E>> @Nullable E enumOrNull(Class<E> type, @Nullable String name) {
        return name == null ? null : Enum.valueOf(type, name);
    }

    /** The connection goes back to the pool as it came. */
    @Override
    public void close() {
        template.execute("DROP TABLE IF EXISTS " + LINES);
        template.execute("RESET statement_timeout");
    }
}
