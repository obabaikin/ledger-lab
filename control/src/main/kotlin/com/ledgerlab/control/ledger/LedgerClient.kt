package com.ledgerlab.control.ledger

import com.ledgerlab.control.ControlProperties
import com.ledgerlab.control.http.Reply
import com.ledgerlab.control.http.ServiceHttp
import com.ledgerlab.control.http.forRun
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
class LedgerClient(props: ControlProperties, objectMapper: ObjectMapper) {
    private val http = ServiceHttp(props.ledger.baseUrl, props.services.requestTimeout, objectMapper)
    private val reconciliationTimeout = props.ledger.reconciliationTimeout

    fun openAccounts(runId: UUID, request: OpenAccountsRequest): Reply<Any?> =
        http.send("POST", LedgerApi.ACCOUNTS_PATH.forRun(runId), request)

    fun reconciliation(runId: UUID): Reply<ReconciliationReport?> =
        http.call("GET", LedgerApi.RECONCILIATION_PATH.forRun(runId), ReconciliationReport::class.java, timeout = reconciliationTimeout)

    fun delete(runId: UUID): Reply<Any?> = http.send("DELETE", LedgerApi.RUN_PATH.forRun(runId))
}
