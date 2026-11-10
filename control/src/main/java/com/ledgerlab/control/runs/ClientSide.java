package com.ledgerlab.control.runs;

import com.ledgerlab.control.generator.ClientReconciliationReport;

public sealed interface ClientSide {

    record Reconciled(ClientReconciliationReport report) implements ClientSide {
    }

    record Missing(String reason) implements ClientSide {
    }
}
