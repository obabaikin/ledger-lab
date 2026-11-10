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
            INSERT INTO journal_entries (accepted_at, transfer_id, account_no, amount_cents)
            SELECT * FROM unnest(
                CAST(:acceptedAts AS timestamptz[]), CAST(:transferIds AS uuid[]), CAST(:accountNos AS integer[]), CAST(:amounts AS bigint[])
            )
            """)
            .param("acceptedAts", Sql.timestamptzArray(entries.stream().map(JournalEntry::acceptedAt).toList()))
            .param("transferIds", entries.stream().map(it -> it.transferId().toString()).toArray(String[]::new))
            .param("accountNos", entries.stream().map(JournalEntry::accountNo).toArray(Integer[]::new))
            .param("amounts", entries.stream().map(JournalEntry::amountCents).toArray(Long[]::new))
            .update();
    }
}
