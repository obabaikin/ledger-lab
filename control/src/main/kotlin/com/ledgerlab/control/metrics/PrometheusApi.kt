package com.ledgerlab.control.metrics

/** Control's model of Prometheus' HTTP API: the instant query and the part of its answer control reads. */
object PrometheusApi {
    const val QUERY_PATH = "/api/v1/query"
    const val SUCCESS = "success"
}

data class PrometheusResponse(val status: String, val data: PrometheusData?)

data class PrometheusData(val resultType: String, val result: List<PrometheusSample>)

/** [value] is a Unix time in seconds and the value as text, as Prometheus sends them. */
data class PrometheusSample(val metric: Map<String, String>, val value: List<Any>) {
    val number: Double get() = value[1].toString().toDouble()
}
