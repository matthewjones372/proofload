package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.Action
import io.github.matthewjones372.kestrel.ScenarioBuilder
import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.StepResult
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.action
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

private const val OK = 200

/**
 * One request, as a value: each of these returns another action rather than
 * changing this one, so a request can be shared between scenarios and read
 * before anything is sent.
 */
class HttpAction internal constructor(
    private val method: String,
    private val baseUrl: String,
    private val path: String,
    private val headers: Map<String, String> = emptyMap(),
    private val body: String? = null,
    private val expected: Int = OK,
    private val timeout: Duration = requestTimeout,
) : Action {

    /**
     * The path template as written. A report keyed on the substituted URL grows
     * a row per user; keyed on `/orders/{id}` it has one row per endpoint.
     */
    val name: String get() = path

    fun header(name: String, value: String): HttpAction = copy(headers = headers + (name to value))

    fun body(body: String): HttpAction = copy(body = body)

    /** The status that counts as a success. Anything else fails the step. */
    fun expecting(status: Int): HttpAction = copy(expected = status)

    fun timeout(timeout: Duration): HttpAction = copy(timeout = timeout)

    override fun run(session: Session): StepResult = action { send(this) }.run(session)

    /** Sends, and records what happened on [scope]. For use inside a step body. */
    fun send(scope: StepScope) {
        val response = exchange(request(), scope) ?: return
        if (response.status != expected) scope.fail("status ${response.status}")
    }

    private fun copy(
        headers: Map<String, String> = this.headers,
        body: String? = this.body,
        expected: Int = this.expected,
        timeout: Duration = this.timeout,
    ): HttpAction = HttpAction(method, baseUrl, path, headers, body, expected, timeout)

    // Folded rather than accumulated: `HttpRequest.Builder` returns itself from
    // every call, so the loop that a builder invites is an expression instead.
    private fun request(): HttpRequest = headers.entries
        .fold(
            HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .method(method, publisher()),
        ) { builder, (name, value) -> builder.header(name, value) }
        .build()

    private fun publisher(): HttpRequest.BodyPublisher =
        body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
}

/** Names the step for the path template, which is the row a report wants. */
fun ScenarioBuilder.exec(request: HttpAction) {
    exec(request.name, request)
}
