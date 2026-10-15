package com.ledgerlab.control.chaos

import com.ledgerlab.control.ControlProperties
import com.ledgerlab.control.http.Reply
import com.ledgerlab.control.http.ServiceHttp
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.Executors

@Component
class LedgerInstances(props: ControlProperties, objectMapper: ObjectMapper) {
    private val instances = props.chaos.ledgerInstances.map { it.name to ServiceHttp(it.url, props.chaos.requestTimeout, objectMapper) }

    /** The names of the instances that answered 2xx, in configured order; the others are taken as not crashed. */
    fun crashAll(): List<String> =
        Executors.newVirtualThreadPerTaskExecutor().use { calls ->
            instances
                .map { (name, http) -> name to calls.submit<Reply<Any?>> { http.send("POST", CRASH_PATH) } }
                .filter { (_, reply) -> reply.get() is Reply.Answered }
                .map { (name, _) -> name }
        }

    private companion object {
        const val CRASH_PATH = "/actuator/crash"
    }
}
