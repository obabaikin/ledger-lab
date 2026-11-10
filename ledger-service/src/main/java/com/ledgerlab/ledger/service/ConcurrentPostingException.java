package com.ledgerlab.ledger.service;

import com.ledgerlab.common.TransferCommand;

public class ConcurrentPostingException extends IllegalStateException {

    public ConcurrentPostingException(TransferCommand command) {
        super("Transfer " + command.transferId() + " of run " + command.runId() + " was recorded concurrently; the batch is retried");
    }
}
