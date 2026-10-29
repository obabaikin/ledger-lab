package com.ledgerlab.control.entry

import com.ledgerlab.control.ControlProperties
import com.ledgerlab.control.http.Reply
import com.ledgerlab.control.http.ServiceHttp
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

class VerificationFailedException : RuntimeException()

class VerifierBusyException : RuntimeException()

@Component
class TurnstileClient(props: ControlProperties, objectMapper: ObjectMapper) {
    private val secret = props.turnstile.secret
    private val http = ServiceHttp(props.turnstile.baseUrl, props.turnstile.requestTimeout, objectMapper)

    data class Outcome(val success: Boolean)

    fun verify(token: String?) {
        if (secret.isEmpty()) return
        if (token.isNullOrBlank()) throw VerificationFailedException()
        when (val reply = http.call("POST", SITEVERIFY_PATH, Outcome::class.java, mapOf("secret" to secret, "response" to token))) {
            is Reply.Answered -> if (reply.body?.success != true) throw VerificationFailedException()
            else -> throw VerifierBusyException()
        }
    }

    companion object {
        const val SITEVERIFY_PATH = "/turnstile/v0/siteverify"
    }
}
