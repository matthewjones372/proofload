package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.Reason
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.Threw
import io.github.matthewjones372.kestrel.TimedOut
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.toJavaDuration

/** How long a request is given before it is a failure rather than a slow success. */
internal val requestTimeout: Duration = Duration.ofSeconds(30)

/**
 * One client for the whole run, negotiating HTTP/2 and falling back to 1.1,
 * with connections reused.
 *
 * A client per user would measure TLS handshakes and connection setup, which is
 * a different experiment from the one almost anyone means to run. Built lazily,
 * so a process that describes a scenario without sending anything does not
 * start the client's threads.
 */
internal val sharedClient: HttpClient by lazy {
    HttpClient.newBuilder()
        // A 302 the test did not expect is a finding. Followed silently, it
        // becomes a timing for a page nobody asked for, under the old row name.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
}

/**
 * The transport this module ships: the JDK's own client, one for the whole run.
 *
 * The default rather than the only one. `docs/what-it-costs.md` measures this
 * path at a lower bound of a couple of thousand a second on four shared cores,
 * and a caller who needs more can hand in a transport built on a client that
 * goes faster — carrying its own dependency, in its own module, without core
 * or this module growing one.
 */
class JdkHttpClient(private val client: HttpClient = sharedClient) : Transport {

    override fun exchange(request: Request): Exchange =
        try {
            Exchange.Answered(if (request.discardingBody) counted(request) else held(request))
        } catch (failure: IOException) {
            Exchange.Failed(failure.reason())
        } catch (interrupted: InterruptedException) {
            // The engine is stopping this user; leave the flag set for
            // whatever checks it next, and report the failure it is.
            Thread.currentThread().interrupt()
            Exchange.Failed(interrupted.className())
        }

    private fun held(request: Request): Response =
        Response.of(client.send(request.asJdk(), HttpResponse.BodyHandlers.ofString()))

    /**
     * Every buffer counted and let go, so a 200 MB export is measured in a heap
     * that could not hold one of them.
     *
     * The counter is atomic rather than a plain `var` because the handler's
     * consumer runs on the client's own thread and this one reads it after
     * `send` returns; one allocation per request, on a path that has just made
     * a round trip.
     */
    private fun counted(request: Request): Response {
        val bytes = AtomicLong()
        val handler = HttpResponse.BodyHandlers.ofByteArrayConsumer { chunk ->
            chunk.ifPresent { bytes.addAndGet(it.size.toLong()) }
        }
        return Response.counting(client.send(request.asJdk(), handler), bytes.get())
    }
}

private fun publisher(body: Body?): HttpRequest.BodyPublisher = when (body) {
    null -> HttpRequest.BodyPublishers.noBody()

    is Body.Text -> HttpRequest.BodyPublishers.ofString(body.text)

    // The two-argument form where a length is known, so the request carries a
    // `content-length`; the one-argument form otherwise, which is chunked. The
    // supplier is called by the JDK per subscription, which is what makes a
    // retry send the body again rather than nothing.
    is Body.Streamed ->
        body.bytes
            ?.let { HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(body.open), it) }
            ?: HttpRequest.BodyPublishers.ofInputStream(body.open)
}

private fun Request.asJdk(): HttpRequest = headers.entries
    .fold(
        HttpRequest.newBuilder(uri)
            .timeout(timeout.toJavaDuration())
            .method(method, publisher(body)),
    ) { builder, (name, value) -> builder.header(name, value) }
    .build()

/**
 * Sends [request] through [transport], turning a failure into one on [scope]
 * and returning null. Nothing throws out of here: an engine reads the step's
 * result to measure a failure, so a target's bad day must not arrive by the
 * same route as a bug in the generator.
 */
internal fun exchange(request: Request, scope: StepScope, transport: Transport): Response? =
    when (val answer = transport.exchange(request)) {
        is Exchange.Answered -> answer.response

        is Exchange.Failed -> {
            scope.fail(answer.reason)
            null
        }
    }

/**
 * The class name, not the message: `ConnectException` is one row in a report,
 * while its message carries a host and a port and would be thousands. A timeout
 * is named instead of classed because it is the one transport failure a reader
 * acts on differently — the target was reachable and did not answer in time.
 */
private fun IOException.reason(): Reason = if (this is HttpTimeoutException) TimedOut else className()

private fun Throwable.className(): Threw = Threw(this::class.simpleName ?: javaClass.name)
