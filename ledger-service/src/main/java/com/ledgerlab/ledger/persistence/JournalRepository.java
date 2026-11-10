package com.ledgerlab.ledger.persistence;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JournalRepository {

    private final JdbcClient jdbc;

    public JournalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(List<JournalEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        jdbc.sql(
            """
            INSERT INTO journal_entries (run_id, transfer_id, account_no, amount_cents)
            SELECT * FROM unnest(
                CAST(:runIds AS uuid[]), CAST(:transferIds AS uuid[]), CAST(:accountNos AS integer[]), CAST(:amounts AS bigint[])
            )
            """)
            .param("runIds", entries.stream().map(it -> it.runId().toString()).toArray(String[]::new))
            .param("transferIds", entries.stream().map(it -> it.transferId().toString()).toArray(String[]::new))
            .param("accountNos", entries.stream().map(JournalEntry::accountNo).toArray(Integer[]::new))
            .param("amounts", entries.stream().map(JournalEntry::amountCents).toArray(Long[]::new))
            .update();
    }
}
