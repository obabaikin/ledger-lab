package com.ledgerlab.control.entry

import com.ledgerlab.control.ControlProperties
import com.ledgerlab.control.http.Reply
import com.ledgerlab.control.http.ServiceHttp
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.Base64

@Component
class MailjetClient(props: ControlProperties, objectMapper: ObjectMapper) {
    private val mail = props.mail
    private val credentials = Base64.getEncoder().encodeToString("${mail.apiKey}:${mail.apiSecret}".toByteArray())
    private val http = ServiceHttp(mail.baseUrl, mail.requestTimeout, objectMapper, mapOf(HttpHeaders.AUTHORIZATION to "Basic $credentials"))

    val configured: Boolean get() = mail.apiKey.isNotEmpty()

    /** Tracking stays off: a tracked link would pass the invite token through the provider's redirect. */
    fun send(to: String, subject: String, text: String): Reply<Any?> = http.send(
        "POST",
        SEND_PATH,
        mapOf(
            "Messages" to listOf(
                mapOf(
                    "From" to mapOf("Email" to mail.from, "Name" to SENDER_NAME),
                    "To" to listOf(mapOf("Email" to to)),
                    "Subject" to subject,
                    "TextPart" to text,
                    "TrackClicks" to TRACKING_OFF,
                    "TrackOpens" to TRACKING_OFF,
                ),
            ),
        ),
    )

    companion object {
        const val SEND_PATH = "/v3.1/send"
        const val TRACKING_OFF = "disabled"
        private const val SENDER_NAME = "Ledger Lab"
    }
}
