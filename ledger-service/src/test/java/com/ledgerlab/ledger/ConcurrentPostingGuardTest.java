package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ledgerlab.common.TransferCommand;
import com.ledgerlab.ledger.domain.Account;
import com.ledgerlab.ledger.domain.AccountStatus;
import com.ledgerlab.ledger.persistence.AccountRepository;
import com.ledgerlab.ledger.persistence.DeferredCreditRepository;
import com.ledgerlab.ledger.persistence.JournalRepository;
import com.ledgerlab.ledger.persistence.TransferRepository;
import com.ledgerlab.ledger.service.ConcurrentPostingException;
import com.ledgerlab.ledger.service.LedgerService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConcurrentPostingGuardTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final TransferRepository transfers = mock(TransferRepository.class);
    private final JournalRepository journal = mock(JournalRepository.class);
    private final DeferredCreditRepository deferredCredits = mock(DeferredCreditRepository.class);
    private final LedgerService ledger = new LedgerService(accounts, transfers, journal, deferredCredits);

    @Test
    void a_transfer_someone_else_recorded_after_the_check_for_duplicates_is_not_posted_a_second_time() {
        var command = new TransferCommand(UUID.randomUUID(), 1, 2, 100, Instant.parse("2026-11-10T10:00:00Z"));
        when(accounts.lockAll(anySet())).thenReturn(List.of(account(1)));
        when(accounts.findAll(anySet())).thenReturn(List.of(account(2)));
        when(transfers.findRecorded(anySet())).thenReturn(Set.of());
        when(transfers.insertAllIfAbsent(anyList(), any())).thenReturn(Set.of());

        assertThatThrownBy(() -> ledger.applyBatch(List.of(command))).isInstanceOf(ConcurrentPostingException.class);

        verify(journal, never()).insertAll(anyList());
        verify(accounts, never()).updateBalances(anyCollection());
        verify(deferredCredits, never()).insertAll(anyMap());
    }

    private static Account account(int accountNo) {
        return new Account(accountNo, 1_000, 1_000, AccountStatus.ACTIVE);
    }
}
