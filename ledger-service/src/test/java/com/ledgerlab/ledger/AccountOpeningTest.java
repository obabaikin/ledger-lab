package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.ledger.domain.Account;
import com.ledgerlab.ledger.domain.AccountStatus;
import com.ledgerlab.ledger.service.AccountOpening;
import com.ledgerlab.ledger.service.AccountSetup;
import com.ledgerlab.ledger.service.LedgerService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AccountOpeningTest extends IntegrationTest {

    @Autowired AccountOpening opening;
    @Autowired LedgerService ledger;

    @Test
    void a_bank_without_accounts_gets_the_clients_accounts_from_1_the_last_ones_blocked_and_the_pool_as_account_0() {
        var opened = opening.open(new AccountSetup(5, 1_000, 2, 70_000));

        assertThat(opened).isEqualTo(6);
        assertThat(accountRepository.findAll()).containsExactly(
            new Account(0, 70_000, 70_000, AccountStatus.ACTIVE),
            new Account(1, 1_000, 1_000, AccountStatus.ACTIVE),
            new Account(2, 1_000, 1_000, AccountStatus.ACTIVE),
            new Account(3, 1_000, 1_000, AccountStatus.ACTIVE),
            new Account(4, 1_000, 1_000, AccountStatus.BLOCKED),
            new Account(5, 1_000, 1_000, AccountStatus.BLOCKED));
    }

    @Test
    void opening_again_as_every_start_does_changes_neither_the_balances_nor_the_number_of_accounts() {
        var setup = new AccountSetup(3, 1_000, 0, POOL_OPENING_BALANCE_CENTS);
        openBank(setup);
        applyOne(ledger, transfer(1, 2, 250));
        awaitCreditsApplied();

        var opened = opening.open(setup);

        assertThat(opened).isZero();
        assertThat(balances()).containsExactlyInAnyOrderEntriesOf(Map.of(1, 750L, 2, 1_250L, 3, 1_000L));
        assertThat(accountRepository.findAll()).hasSize(4);
    }

    @Test
    void a_larger_count_opens_the_missing_accounts_and_leaves_the_others_as_they_are() {
        openBank(new AccountSetup(3, 1_000, 1, POOL_OPENING_BALANCE_CENTS));
        applyOne(ledger, transfer(1, 2, 250));
        awaitCreditsApplied();

        var opened = opening.open(new AccountSetup(5, 2_000, 1, 5));

        assertThat(opened).isEqualTo(2);
        assertThat(balances()).containsExactlyInAnyOrderEntriesOf(Map.of(1, 750L, 2, 1_250L, 3, 1_000L, 4, 2_000L, 5, 2_000L));
        assertThat(poolBalance()).isEqualTo(POOL_OPENING_BALANCE_CENTS);
        assertThat(accountRepository.findAll()).extracting(Account::status).containsExactly(
            AccountStatus.ACTIVE, AccountStatus.ACTIVE, AccountStatus.ACTIVE, AccountStatus.BLOCKED, AccountStatus.ACTIVE, AccountStatus.BLOCKED);
    }
}
