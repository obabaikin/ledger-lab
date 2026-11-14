package com.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlab.ledger.domain.AccountMismatch;
import com.ledgerlab.ledger.domain.PeriodCheck;
import com.ledgerlab.ledger.domain.ReconciliationCheck;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PeriodCheckVerdictTest {

    private static final Instant STARTED = Instant.parse("2026-11-14T10:10:00Z");
    private static final Instant MADE = STARTED.plusSeconds(3);

    private static PeriodCheck check(long debits, long credits, long invalidJournal, long outOfBalance) {
        return new PeriodCheck(6, debits, credits, invalidJournal, List.of(), true, outOfBalance, List.of(), STARTED, MADE);
    }

    @Test
    void is_balanced_when_every_check_passes() {
        var check = check(300, 300, 0, 0);

        assertThat(check.balanced()).isTrue();
        assertThat(check.failedChecks()).isEmpty();
    }

    @Test
    void names_the_check_that_failed() {
        assertThat(check(300, 299, 0, 0).failedChecks()).containsExactly(ReconciliationCheck.JOURNAL_DEBITS_EQUAL_CREDITS);
        assertThat(check(300, 300, 1, 0).failedChecks()).containsExactly(ReconciliationCheck.JOURNAL_MATCHES_TRANSFER_LOG);
        assertThat(check(300, 300, 0, 1).failedChecks()).containsExactly(ReconciliationCheck.EVERY_ACCOUNT_MATCHES_JOURNAL);
        assertThat(check(300, 299, 1, 1).balanced()).isFalse();
    }

    @Test
    void a_kept_check_reads_back_as_it_was_written() {
        var mapper = new ObjectMapper();
        var check = new PeriodCheck(4, 150, 150, 1, List.of(UUID.randomUUID()), true, 1, List.of(new AccountMismatch(3, 0, 1)), STARTED, MADE);

        assertThat(mapper.readValue(mapper.writeValueAsString(check), PeriodCheck.class)).isEqualTo(check);
    }
}
