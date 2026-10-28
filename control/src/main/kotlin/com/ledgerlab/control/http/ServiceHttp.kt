package com.ledgerlab.control.http

import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpConnectTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** What a service did with a call: the caller decides which codes are final for it. */
sealed interface Reply<out T> {
    data class Answered<T>(val status: Int, val body: T) : Reply<T>

    data class Refused(val status: Int, val error: String) : Reply<Nothing>

    /** Timed out or cut off after connecting: the call may or may not have taken effect. */
    data object NoAnswer : Reply<Nothing>

    /** The call never reached the service, so sending it again cannot repeat its effect. */
    data object NotConnected : Reply<Nothing>

    /** A 2xx this client cannot read, e.g. from a newer service: it took effect, but tells nothing. */
    data object Unreadable : Reply<Nothing>
}

class ServiceHttp(
    private val baseUrl: String,
    private val timeout: Duration,
    private val objectMapper: ObjectMapper,
    private val headers: Map<String, String> = emptyMap(),
) {

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build()

    fun <T : Any> call(
        method: String,
        path: String,
        answer: Class<T>?,
        body: Any? = null,
        timeout: Duration = this.timeout,
    ): Reply<T?> {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(it)) }
            ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header(CONTENT_TYPE, JSON)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .method(method, publisher)
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: ConnectException) {
            return Reply.NotConnected
        } catch (_: HttpConnectTimeoutException) {
            return Reply.NotConnected
        } catch (_: IOException) {
            return Reply.NoAnswer
        }
        val status = response.statusCode()
        if (status !in SUCCESS) return Reply.Refused(status, errorOf(response.body()) ?: "HTTP $status")
        if (answer == null) return Reply.Answered(status, null)
        val body = try {
            objectMapper.readValue(response.body(), answer)
        } catch (_: JacksonException) {
            null
        }
        return if (body == null) Reply.Unreadable else Reply.Answered(status, body)
    }

    fun send(method: String, path: String, body: Any? = null): Reply<Any?> = call<Any>(method, path, null, body)

    private fun errorOf(body: String?): String? =
        runCatching { objectMapper.readTree(body).get(ERROR_FIELD)?.asString() }.getOrNull()

    private companion object {
        val SUCCESS = 200..299
        const val CONTENT_TYPE = "Content-Type"
        const val JSON = "application/json"
        const val ERROR_FIELD = "error"
    }
}

/** 5xx, no answer or an answer this client cannot read: the call is repeated on a later tick, within the phase's deadline. */
val Reply<*>.isTransient: Boolean get() =
    this is Reply.NoAnswer || this is Reply.NotConnected || this is Reply.Unreadable || (this is Reply.Refused && status >= SERVER_ERROR)

private const val SERVER_ERROR = 500

fun String.forRun(runId: java.util.UUID): String = replace("{runId}", runId.toString())
