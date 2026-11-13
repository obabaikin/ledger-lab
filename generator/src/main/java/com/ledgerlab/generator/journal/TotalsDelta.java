package com.ledgerlab.generator.journal;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

record TotalsDelta(long sent, long acknowledged, long failed, long unknownAccepted, long unknownNotAccepted) {

    private static final String INSERT =
        "INSERT INTO client_totals_delta (sent, acknowledged, failed, unknown_accepted, unknown_not_accepted) VALUES (?, ?, ?, ?, ?)";

    static TotalsDelta of(long sent, List<JournalOutcome> stored) {
        long acknowledged = 0;
        long failed = 0;
        long unknownAccepted = 0;
        long unknownNotAccepted = 0;
        for (var outcome : stored) {
            switch (outcome) {
                case ACKNOWLEDGED -> acknowledged++;
                case FAILED -> failed++;
                case UNKNOWN_ACCEPTED -> unknownAccepted++;
                case UNKNOWN_NOT_ACCEPTED -> unknownNotAccepted++;
            }
        }
        return new TotalsDelta(sent, acknowledged, failed, unknownAccepted, unknownNotAccepted);
    }

    /** In the caller's transaction, so the totals move with the journal or not at all. */
    void insert(JdbcTemplate jdbc) {
        if (sent == 0 && acknowledged == 0 && failed == 0 && unknownAccepted == 0 && unknownNotAccepted == 0) {
            return;
        }
        jdbc.update(INSERT, sent, acknowledged, failed, unknownAccepted, unknownNotAccepted);
    }
}
