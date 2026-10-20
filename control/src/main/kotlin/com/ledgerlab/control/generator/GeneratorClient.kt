package com.ledgerlab.control.generator

import com.ledgerlab.control.ControlProperties
import com.ledgerlab.control.http.Reply
import com.ledgerlab.control.http.ServiceHttp
import com.ledgerlab.control.http.forRun
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
class GeneratorClient(props: ControlProperties, objectMapper: ObjectMapper) {
    private val http = ServiceHttp(props.generator.baseUrl, props.services.requestTimeout, objectMapper)
    private val reconciliationTimeout = props.generator.reconciliationTimeout

    fun start(request: StartSimulationRequest): Reply<Any?> = http.send("POST", GeneratorApi.SIMULATIONS_PATH, request)

    fun status(runId: UUID): Reply<SimulationStatus?> =
        http.call("GET", GeneratorApi.SIMULATION_PATH.forRun(runId), SimulationStatus::class.java)

    fun stop(runId: UUID): Reply<Any?> = http.send("POST", GeneratorApi.STOP_PATH.forRun(runId))

    fun reconciliation(runId: UUID): Reply<ClientReconciliationReport?> =
        http.call("GET", GeneratorApi.RECONCILIATION_PATH.forRun(runId), ClientReconciliationReport::class.java, timeout = reconciliationTimeout)

    fun releaseJournal(runId: UUID): Reply<Any?> = http.send("DELETE", GeneratorApi.JOURNAL_PATH.forRun(runId))
}
