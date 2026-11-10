package com.ledgerlab.ledger.service;

import com.ledgerlab.ledger.domain.Account;
import com.ledgerlab.ledger.persistence.AccountRepository;
import com.ledgerlab.ledger.pool.PoolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

/**
 * The bank's accounts are opened once: every start adds the ones the configured count asks for and that are not there,
 * and never touches an existing one. Before the consumers start, so no transfer meets a bank without accounts.
 */
@Component
@DependsOnDatabaseInitialization
public class AccountOpening implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AccountOpening.class);

    private final AccountRepository accounts;
    private final AccountsProperties props;
    private final PoolProperties pool;

    public AccountOpening(AccountRepository accounts, AccountsProperties props, PoolProperties pool) {
        this.accounts = accounts;
        this.props = props;
        this.pool = pool;
    }

    @Override
    public void afterPropertiesSet() {
        var opened = open(new AccountSetup(props.count(), props.openingBalanceCents(), props.blocked(), pool.openingBalanceCents()));
        if (opened > 0) {
            log.info("Opened {} accounts", opened);
        }
    }

    /** Client accounts are numbered from 1; the last {@link AccountSetup#blockedAccounts} of them are blocked. */
    public int open(AccountSetup setup) {
        var firstBlocked = setup.accountCount() - setup.blockedAccounts() + 1;
        return accounts.openMissing(Account.POOL_ACCOUNT_NO, Account.POOL_ACCOUNT_NO, firstBlocked, setup.poolOpeningBalanceCents())
            + accounts.openMissing(Account.FIRST_CLIENT_ACCOUNT_NO, setup.accountCount(), firstBlocked, setup.openingBalanceCents());
    }
}
