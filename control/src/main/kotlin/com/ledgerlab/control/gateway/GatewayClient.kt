package com.ledgerlab.control.gateway

import com.ledgerlab.control.ControlProperties
import com.ledgerlab.control.http.Reply
import com.ledgerlab.control.http.ServiceHttp
import com.ledgerlab.control.http.forRun
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
class GatewayClient(props: ControlProperties, objectMapper: ObjectMapper) {
    private val http = ServiceHttp(props.gateway.baseUrl, props.services.requestTimeout, objectMapper)
    private val summaryTimeout = props.gateway.summaryTimeout

    fun summary(runId: UUID): Reply<RunSummary?> =
        http.call("GET", GatewayApi.SUMMARY_PATH.forRun(runId), RunSummary::class.java, timeout = summaryTimeout)

    fun close(runId: UUID): Reply<ClosedPeriod?> = http.call("POST", GatewayApi.CLOSE_PATH.forRun(runId), ClosedPeriod::class.java)

    fun delete(runId: UUID): Reply<Any?> = http.send("DELETE", GatewayApi.RUN_PATH.forRun(runId))
}
