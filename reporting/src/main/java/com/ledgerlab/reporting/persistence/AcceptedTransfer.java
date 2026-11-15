package com.ledgerlab.reporting.persistence;

public record AcceptedTransfer(TransferKey key, long amountCents) {
}
