package com.ledgerlab.generator.load;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMinuteStore implements MinuteStore {

    private static final String ADD =
        """
        INSERT INTO response_minutes AS m (minute, total, errors, busy) VALUES (?, ?, ?, ?)
        ON CONFLICT (minute) DO UPDATE SET total = m.total + EXCLUDED.total, errors = m.errors + EXCLUDED.errors, busy = m.busy + EXCLUDED.busy
        """;

    private final JdbcTemplate template;
    private final JdbcClient jdbc;

    public JdbcMinuteStore(JdbcTemplate template, JdbcClient jdbc) {
        this.template = template;
        this.jdbc = jdbc;
    }

    @Override
    public void add(List<MinuteCounts> counts) {
        template.batchUpdate(ADD, counts, counts.size(), (statement, minute) -> {
            statement.setTimestamp(1, Timestamp.from(minute.minute()));
            statement.setLong(2, minute.total());
            statement.setLong(3, minute.errors());
            statement.setLong(4, minute.busy());
        });
    }

    @Override
    public List<MinuteCounts> between(Instant first, Instant last) {
        return jdbc.sql("SELECT minute, total, errors, busy FROM response_minutes WHERE minute BETWEEN :first AND :last ORDER BY minute")
            .param("first", Timestamp.from(first))
            .param("last", Timestamp.from(last))
            .query((rs, row) -> new MinuteCounts(rs.getTimestamp("minute").toInstant(), rs.getLong("total"), rs.getLong("errors"), rs.getLong("busy")))
            .list();
    }

    @Override
    public void dropBefore(Instant minute) {
        jdbc.sql("DELETE FROM response_minutes WHERE minute < :minute").param("minute", Timestamp.from(minute)).update();
    }
}
