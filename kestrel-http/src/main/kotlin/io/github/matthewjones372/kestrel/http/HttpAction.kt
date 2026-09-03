package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.Action
import io.github.matthewjones372.kestrel.ScenarioBuilder
import io.github.matthewjones372.kestrel.SessionKey
import io.github.matthewjones372.kestrel.StepScope
import java.net.URI
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.toKotlinDuration

/** What a retry is: how many more sends, on what, and the first wait between them. */
internal data class Retries(
    val times: Int,
    val on: (Response) -> Boolean,
    val backingOff: kotlin.time.Duration,
)

/**
 * The first wait between attempts, doubling after that.
 *
 * A hundred milliseconds because a target that answered 503 is usually
 * shedding load, and sending again immediately is the behaviour that keeps it
 * shedding.
 */
internal val firstBackoff: kotlin.time.Duration = 100.milliseconds

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
    private val following: Int = 0,
    private val retries: Retries? = null,
) : Action {

    /**
     * The path template as written. A report keyed on the substituted URL grows
     * a row per user; keyed on `/orders/{id}` it has one row per endpoint.
     */
    val name: String get() = path

    fun header(name: String, value: String): HttpAction = copy(headers = headers + (name to value))

    /**
     * The request body, filled per user from the session where it carries
     * `{name}` — the rule a path template already uses, and the same
     * `UnfilledPath` failure naming the key when the session has nothing under
     * it.
     *
     * A body with no `{` costs nothing and arrives exactly as written, which
     * is every JSON body full of braces that are not placeholders.
     */
    fun body(body: String): HttpAction = copy(body = body)

    /** The status that counts as a success. Anything else fails the step. */
    fun expecting(status: Int): HttpAction = copy(expected = status)

    fun timeout(timeout: Duration): HttpAction = copy(timeout = timeout)

    /**
     * Follows a redirect, up to [max] hops, and judges the step on the response
     * the chain lands on. Off unless asked for, because the client never
     * follows: a 302 nobody declared is a finding, and followed silently it
     * becomes a timing for a page nobody asked for under this request's name.
     * A chain longer than [max] fails the step as [TooManyRedirects].
     */
    fun following(max: Int = 1): HttpAction = copy(following = max)

    /**
     * Sends again, up to [times] more, while [on] holds of the response.
     *
     * Each attempt is a trip counted in `attempts`, and the step's service
     * time is the **last** attempt's — not the sum, and not the sum plus the
     * waits between them. A retry folded into one measurement reports the
     * target as slower than it is and hides that it answered wrongly first,
     * and a p99 that includes a backoff is a number describing this tool's
     * patience rather than the target.
     *
     * The wait doubles from [backingOff] and is not measured: it is time the
     * user spends waiting, which lengthens the journey and belongs to no
     * request.
     *
     * Asked for rather than default. A retry changes what is being measured —
     * a target that fails one request in ten looks perfect behind two retries
     * — so nothing here retries unless a caller said to.
     */
    fun retrying(
        times: Int,
        on: (Response) -> Boolean,
        backingOff: kotlin.time.Duration = firstBackoff,
    ): HttpAction {
        require(times > 0) { "a retry sends again at least once, but times was $times" }
        return copy(retries = Retries(times, on, backingOff))
    }

    /**
     * Asks [holds] of the response, failing the step under [name] when it does
     * not. The whole response body is held in memory to be read, so a request
     * that streams something large cannot also be checked.
     */
    fun checking(name: String, holds: (Response) -> Boolean): HttpAction = copy(checks = checks + Check(name, holds))

    /** Takes a value out of the response and puts it in the session under [key]. */
    fun <T : Any> capture(key: SessionKey<T>, extract: (Response) -> T?): HttpAction =
        copy(captures = captures + Capture(key, extract))

    override fun run(scope: StepScope) {
        sendTo(scope)
    }

    /** What left, for a trace. Nothing at all where no trace is listening. */
    private fun narrate(url: String, sending: String?, scope: StepScope) {
        if (!scope.narrating) return
        scope.note("$method ${origin.baseUrl}$url")
        headers.forEach { (name, value) -> scope.note("> $name: $value") }
        // The filled body rather than the template: a trace exists to show
        // what left, not what was written.
        sending?.let { scope.note("> $it") }
    }

    /** And what came back. */
    private fun narrate(response: Response, scope: StepScope) {
        if (!scope.narrating) return
        scope.note("< ${response.status}")
        response.body.takeIf { it.isNotBlank() }?.let { scope.note("< $it") }
    }

    /** Sends, and records what happened on [scope]. Reached through [send]. */
    internal fun sendTo(scope: StepScope): Response? {
        val url = path.fill(scope) ?: return null
        // Filled by the same rule the path uses, and before anything is sent: a
        // body with a hole in it is not a request to make, and a target given
        // one would answer for a mistake in this scenario.
        val sending = body?.let { it.fillBody(scope) ?: return null }
        narrate(url, sending, scope)
        val response = attempts(url, sending, scope) ?: return null
        narrate(response, scope)
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

    /**
     * The request, sent as many times as [retries] asks for while its condition
     * holds, timing each attempt on its own.
     *
     * The last attempt's duration is reported as the step's sample, so the
     * earlier attempts and the waits between them are counted in `attempts`
     * and are not in the latency. Without a retry this is one send and the
     * engine times it, exactly as before.
     */
    // The ban is against a parked *carrier*, and this runs on the user's own
    // virtual thread, where sleep unmounts rather than holding one — the same
    // exemption the engine's `pause` takes, for the same reason. Scheduling
    // the next attempt instead would hand the rest of the journey to another
    // thread and lose the session the step is holding.
    @Suppress("ForbiddenMethodCall")
    private fun attempts(url: String, sending: String?, scope: StepScope): Response? {
        val retries = retries
            ?: return follow(Hop(URI.create(origin.baseUrl + url), method, sending), following, scope)

        var waitFor = retries.backingOff
        var left = retries.times
        while (true) {
            val startedAt = System.nanoTime()
            val response = follow(Hop(URI.create(origin.baseUrl + url), method, sending), following, scope)
            val took = (System.nanoTime() - startedAt).nanoseconds
            if (response == null || left == 0 || !retries.on(response)) {
                // The last attempt is the measurement, whether it worked or
                // not: a transport failure has already failed the step, and
                // the sample says how long the try that decided it took.
                if (response != null) scope.sample(took)
                return response
            }
            left--
            scope.attempted()
            Thread.sleep(waitFor.inWholeMilliseconds)
            waitFor *= 2
        }
    }

    /**
     * Walks the chain, hop by hop, and answers with the response it ends on.
     *
     * Followed here rather than by the client, which is built never to: each hop
     * is a request this makes, so a hop can carry the cookies the one before it
     * set and none of them can arrive as somebody else's measurement.
     */
    private tailrec fun follow(hop: Hop, hopsLeft: Int, scope: StepScope): Response? {
        val response = exchange(request(hop, scope), scope, origin.transport) ?: return null
        // Kept whatever the status was, unlike a capture: a cookie is state the
        // target set on the user, not a value this step asked for, and dropping
        // the one that came with an unexpected status would make the next step
        // fail as a sign-in problem rather than as the status that broke.
        if (origin.cookies) scope.rememberCookies(response)
        val next = if (following <= 0) null else response.hopFrom(hop)
        if (next == null) return response
        if (hopsLeft <= 0) {
            scope.fail(TooManyRedirects(following))
            return null
        }
        // The hop is another round trip to the target under this step's name.
        // One request with one service time, and the hops counted beside it, so
        // a sign-in that costs a 302 and a 200 does not read as one request.
        scope.attempted()
        return follow(next, hopsLeft - 1, scope)
    }

    private fun copy(
        headers: Map<String, String> = this.headers,
        body: String? = this.body,
        expected: Int = this.expected,
        timeout: Duration = this.timeout,
        checks: List<Check> = this.checks,
        captures: List<Capture<*>> = this.captures,
        following: Int = this.following,
        retries: Retries? = this.retries,
    ): HttpAction =
        HttpAction(
            method,
            origin,
            path,
            headers,
            body,
            expected,
            timeout,
            checks,
            captures,
            traced,
            following,
            retries,
        )

    // Folded rather than accumulated: `HttpRequest.Builder` returns itself from
    // every call, so the loop that a builder invites is an expression instead.
    private fun request(hop: Hop, scope: StepScope): Request = Request(
        method = hop.method,
        uri = hop.uri,
        // The trace headers are put on here rather than by the transport: what
        // a run is followed by downstream is this module's to decide, and a
        // transport that had to add them could forget to.
        headers = tracing(traced, scope) + headersFor(scope),
        body = hop.body,
        timeout = timeout.toKotlinDuration(),
    )

    // `HttpRequest.Builder.header` appends, so a jar and a hand-written cookie
    // header would send two of them; the one the scenario wrote wins.
    private fun headersFor(scope: StepScope): Map<String, String> {
        val sending = if (origin.cookies) scope.cookieHeader() else null
        return when {
            sending == null || headers.keys.any { it.equals(COOKIE, ignoreCase = true) } -> headers
            else -> mapOf(COOKIE to sending) + headers
        }
    }
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
