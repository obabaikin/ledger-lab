package com.ledgerlab.control.ledger

import com.ledgerlab.control.ControlProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient

@Component
class LedgerReadiness(ledger: ControlProperties.Ledger) {

    @Autowired
    constructor(props: ControlProperties) : this(props.ledger)

    private val log = LoggerFactory.getLogger(javaClass)

    private val http = RestClient.builder()
        .baseUrl(ledger.baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(ledger.readinessTimeout).build())
                .apply { setReadTimeout(ledger.readinessTimeout) },
        )
        .build()

    @Volatile private var lastAnswer: Boolean? = null

    fun isReady(): Boolean {
        val ready = try {
            http.get().uri(LedgerApi.READINESS_PATH).retrieve().toBodilessEntity()
            true
        } catch (e: RestClientException) {
            if (lastAnswer != false) log.info("Ledger not ready, runs wait in the queue: {}", e.message)
            false
        }
        if (ready && lastAnswer != true) log.info("Ledger ready")
        lastAnswer = ready
        return ready
    }
}
