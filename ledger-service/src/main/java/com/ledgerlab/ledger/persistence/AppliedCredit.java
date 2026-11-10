package com.ledgerlab.ledger.persistence;

import java.time.Duration;

public record AppliedCredit(int accountNo, long amountCents, Duration waited) {
}
