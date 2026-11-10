package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.common.TransferOutcome;
import com.ledgerlab.ledger.domain.AccountStatus;
import com.ledgerlab.ledger.domain.TransferKind;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** The enums mirror CHECK constraints in the schema; this fails if one side gains a value the other lacks. */
@Transactional
class SchemaEnumConsistencyTest extends IntegrationTest {

    private static final String ANY_REASON = "SAME_ACCOUNT";

    @Autowired JdbcClient jdbc;

    @Test
    void every_TransferOutcome_and_TransferKind_is_accepted_by_the_transfers_table() {
        for (var kind : TransferKind.values()) {
            for (var outcome : TransferOutcome.values()) {
                var inserted = jdbc.sql(
                    """
                    INSERT INTO transfers (accepted_at, transfer_id, kind, from_account, to_account, amount_cents, outcome, reject_reason)
                    VALUES (:acceptedAt, :transferId, :kind, 1, 2, 1, :outcome, :reason)
                    """)
                    .param("acceptedAt", Timestamp.from(period.start()))
                    .param("transferId", UUID.randomUUID())
                    .param("kind", kind.name())
                    .param("outcome", outcome.name())
                    .param("reason", outcome == TransferOutcome.REJECTED ? ANY_REASON : null)
                    .update();

                assertThat(inserted).isEqualTo(1);
            }
        }
    }

    @Test
    void every_AccountStatus_is_accepted_by_the_accounts_table() {
        var accountNo = 0;
        for (var status : AccountStatus.values()) {
            accountNo++;
            var inserted = jdbc.sql(
                "INSERT INTO accounts (account_no, opening_balance_cents, balance_cents, status) VALUES (:no, 0, 0, :status)")
                .param("no", accountNo)
                .param("status", status.name())
                .update();

            assertThat(inserted).isEqualTo(1);
        }
    }
}
