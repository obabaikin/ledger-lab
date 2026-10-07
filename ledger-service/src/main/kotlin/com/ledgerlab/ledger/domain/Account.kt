package com.ledgerlab.ledger.domain

import java.util.UUID

enum class AccountStatus { ACTIVE, BLOCKED }

data class Account(
    val runId: UUID,
    val accountNo: Int,
    val openingBalanceCents: Long,
    val balanceCents: Long,
    val status: AccountStatus,
    val dailyLimitCents: Long,
    val dailySpentCents: Long,
)
