package com.ledgerlab.ledger.persistence;

import com.ledgerlab.ledger.domain.AccountKey;
import java.time.Duration;

public record AppliedCredit(AccountKey account, long amountCents, Duration waited) {
}
