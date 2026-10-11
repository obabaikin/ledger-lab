package com.ledgerlab.ledger.domain

import com.ledgerlab.common.RejectReason

sealed interface TransferResult {
    data object Posted : TransferResult
    data class Rejected(val reason: RejectReason) : TransferResult
    data object AlreadyProcessed : TransferResult
}
