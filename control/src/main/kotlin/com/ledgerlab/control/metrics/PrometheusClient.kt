package com.ledgerlab.control.metrics

import com.ledgerlab.control.ControlProperties
import com.ledgerlab.control.http.Reply
import com.ledgerlab.control.http.ServiceHttp
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Component
class PrometheusClient(props: ControlProperties, objectMapper: ObjectMapper) {
    private val http = ServiceHttp(props.prometheus.baseUrl, props.prometheus.requestTimeout, objectMapper)

    fun query(query: String): List<PrometheusSample>? {
        val reply = http.call("GET", "${PrometheusApi.QUERY_PATH}?query=${URLEncoder.encode(query, StandardCharsets.UTF_8)}", PrometheusResponse::class.java)
        val body = (reply as? Reply.Answered)?.body ?: return null
        return if (body.status == PrometheusApi.SUCCESS) body.data?.result else null
    }
}
