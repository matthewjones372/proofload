package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.Action
import io.github.matthewjones372.kestrel.ScenarioBuilder
import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.SessionKey
import io.github.matthewjones372.kestrel.StepResult
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.action
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

private const val OK = 200
private const val COOKIE = "cookie"

/**
 * One request, as a value: each of these returns another action rather than
 * changing this one, so a request can be shared between scenarios and read
 * before anything is sent.
 */
class HttpAction internal constructor(
    private val method: String,
    private val origin: Http,
    private val path: String,
    private val headers: Map<String, String> = emptyMap(),
    private val body: String? = null,
    private val expected: Int = OK,
    private val timeout: Duration = requestTimeout,
    private val checks: List<Check> = emptyList(),
    private val captures: List<Capture<*>> = emptyList(),
    private val traced: Boolean = false,
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

    /**
     * Asks [holds] of the response, failing the step under [name] when it does
     * not. The whole response body is held in memory to be read, so a request
     * that streams something large cannot also be checked.
     */
    fun checking(name: String, holds: (Response) -> Boolean): HttpAction = copy(checks = checks + Check(name, holds))

    /** Takes a value out of the response and puts it in the session under [key]. */
    fun <T : Any> capture(key: SessionKey<T>, extract: (Response) -> T?): HttpAction =
        copy(captures = captures + Capture(key, extract))

    override fun run(session: Session): StepResult = action { sendTo(this) }.run(session)

    /** Sends, and records what happened on [scope]. Reached through [send]. */
    internal fun sendTo(scope: StepScope): Response? {
        val url = path.fill(scope) ?: return null
        val response = exchange(request(url, scope), scope) ?: return null
        // Kept whatever the status was, unlike a capture: a cookie is state the
        // target set on the user, not a value this step asked for, and dropping
        // the one that came with an unexpected status would make the next step
        // fail as a sign-in problem rather than as the status that broke.
        if (origin.cookies) scope.rememberCookies(response)
        if (response.status != expected) {
            // Nothing is captured out of a response the request did not ask
            // for: a body from an error page in the session is a failure that
            // reappears as a stranger, several steps later.
            scope.fail(HttpStatus(response.status))
            return null
        }
        val rejected = checks.firstOrNull { it.rejects(response) }
        if (rejected != null) {
            // Same reason the status branch above captures nothing: a body the
            // check has just called wrong is not one to take values out of.
            scope.fail(CheckFailed(rejected.name))
            return null
        }
        captures.forEach { it.applyTo(scope, response) }
        return response
    }

    private fun copy(
        headers: Map<String, String> = this.headers,
        body: String? = this.body,
        expected: Int = this.expected,
        timeout: Duration = this.timeout,
        checks: List<Check> = this.checks,
        captures: List<Capture<*>> = this.captures,
    ): HttpAction = HttpAction(method, origin, path, headers, body, expected, timeout, checks, captures, traced)

    // Folded rather than accumulated: `HttpRequest.Builder` returns itself from
    // every call, so the loop that a builder invites is an expression instead.
    private fun request(url: String, scope: StepScope): HttpRequest = headersFor(scope).entries
        .fold(
            HttpRequest.newBuilder(URI.create(origin.baseUrl + url))
                .timeout(timeout)
                .method(method, publisher())
                .tracing(traced),
        ) { builder, (name, value) -> builder.header(name, value) }
        .build()

    // `HttpRequest.Builder.header` appends, so a jar and a hand-written cookie
    // header would send two of them; the one the scenario wrote wins.
    private fun headersFor(scope: StepScope): Map<String, String> {
        val sending = if (origin.cookies) scope.cookieHeader() else null
        return when {
            sending == null || headers.keys.any { it.equals(COOKIE, ignoreCase = true) } -> headers
            else -> mapOf(COOKIE to sending) + headers
        }
    }

    private fun publisher(): HttpRequest.BodyPublisher =
        body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
}

/** Names the step for the path template, which is the row a report wants. */
fun ScenarioBuilder.exec(request: HttpAction) {
    exec(request.name, request)
}

/**
 * Sends [request] and records what happened on this step.
 *
 * The verb takes the request because the request is the thing being sent; the
 * scope it reports to is the one the step body is already running in. The
 * response comes back for a step that needs to read it, and is ignored by the
 * many that do not.
 */
fun StepScope.send(request: HttpAction): Response? = request.sendTo(this)
