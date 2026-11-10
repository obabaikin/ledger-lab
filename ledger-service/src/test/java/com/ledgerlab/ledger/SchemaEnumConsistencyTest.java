package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.ledger.domain.AccountStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** Kotlin enums mirror CHECK constraints in the schema; this fails if one side gains a value the other lacks. */
@Transactional
class SchemaEnumConsistencyTest extends IntegrationTest {

    @Autowired JdbcClient jdbc;

    @Test
    void every_TransferOutcome_is_accepted_by_the_transfers_table() {
        var runId = UUID.randomUUID();
        for (var outcome : TransferOutcome.values()) {
            var inserted = jdbc.sql(
                """
                INSERT INTO transfers (run_id, transfer_id, from_account, to_account, amount_cents, outcome, reject_reason)
                VALUES (:runId, :transferId, 1, 2, 1, :outcome, :reason)
                """)
                .param("runId", runId)
                .param("transferId", UUID.randomUUID())
                .param("outcome", outcome.name())
                .param("reason", outcome == TransferOutcome.REJECTED ? "SAME_ACCOUNT" : null)
                .update();
            assertThat(inserted).isEqualTo(1);
        }
    }

    @Test
    void every_AccountStatus_is_accepted_by_the_accounts_table() {
        var runId = UUID.randomUUID();
        var accountNo = 0;
        for (var status : AccountStatus.values()) {
            accountNo++;
            var inserted = jdbc.sql(
                """
                INSERT INTO accounts (run_id, account_no, opening_balance_cents, balance_cents, status, daily_limit_cents)
                VALUES (:runId, :no, 0, 0, :status, 0)
                """)
                .param("runId", runId)
                .param("no", accountNo)
                .param("status", status.name())
                .update();
            assertThat(inserted).isEqualTo(1);
        }
    }
}
