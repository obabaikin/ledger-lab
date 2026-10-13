package com.ledgerlab.control.reporting

import com.ledgerlab.control.ControlProperties
import com.ledgerlab.control.http.Reply
import com.ledgerlab.control.http.ServiceHttp
import com.ledgerlab.control.http.forRun
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
class ReportingClient(props: ControlProperties, objectMapper: ObjectMapper) {
    private val http = ServiceHttp(props.reporting.baseUrl, props.services.requestTimeout, objectMapper)

    fun stats(runId: UUID): Reply<RunStats?> = http.call("GET", ReportingApi.STATS_PATH.forRun(runId), RunStats::class.java)

    fun delete(runId: UUID): Reply<Any?> = http.send("DELETE", ReportingApi.RUN_PATH.forRun(runId))
}
