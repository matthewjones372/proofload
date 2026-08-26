package io.github.matthewjones372.kestrel.pelican

import io.github.matthewjones372.kestrel.RunRecorder
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.ClientResponse
import io.github.matthewjones372.pelican.ClientTransport
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletionStage
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Pelican's transport, over the JDK client.
 *
 * `ClientTransport` is one method with Pekko behind it in another module, so
 * implementing it here puts a generated typed client inside a load test —
 * unchanged, on virtual threads, with each endpoint's declared failures still
 * decoded into their sealed types, and no actor system for this tool to time.
 *
 * @param templates the path templates a request's URL is matched against, so a
 *   report has one row per endpoint rather than one per id. Pass the ones the
 *   descriptions already name.
 */
fun kestrelTransport(
    recorder: RunRecorder? = null,
    templates: List<String> = emptyList(),
    timeout: Duration = REQUEST_TIMEOUT,
): ClientTransport = KestrelTransport(recorder, templates.map(::Template), timeout)

private class KestrelTransport(
    private val recorder: RunRecorder?,
    private val templates: List<Template>,
    private val timeout: Duration,
) : ClientTransport {

    // One client for the run. A client per user measures TLS handshakes and
    // connection setup, which is a different experiment from the one anyone
    // means to run.
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun send(request: ClientRequest): CompletionStage<ClientResponse> {
        val step = "${request.method} ${nameOf(request.url)}"
        val startedAt = System.nanoTime()

        return client.sendAsync(request.asHttpRequest(timeout), HttpResponse.BodyHandlers.ofByteArray())
            .handle { response, failure ->
                record(step, startedAt, failure?.let(::reasonFor) ?: statusFailure(response.statusCode()))
                // The exchange's own outcome is Pelican's to interpret: an
                // endpoint may well declare the 404 this recorded as a failure.
                if (failure != null) throw failure
                ClientResponse(response.statusCode(), response.headersAsPairs(), ByteArrayInputStream(response.body()))
            }
    }

    private fun record(step: String, startedAt: Long, failure: String?) {
        // No departure to be late against here: this transport is called from
        // inside a step the engine already timed, so the backlog is that step's
        // to report and zero is the honest number rather than a guess.
        recorder?.record(
            step = step,
            failure = failure,
            serviceTime = (System.nanoTime() - startedAt).nanoseconds,
            schedulingDelay = kotlin.time.Duration.ZERO,
        )
    }

    private fun nameOf(url: String): String {
        val path = URI.create(url).path
        return templates.firstOrNull { it.matches(path) }?.template ?: path.generalised()
    }
}

private fun statusFailure(status: Int): String? = if (status in SUCCESS) null else "status $status"

// The client wraps what went wrong in a CompletionException, so the cause is
// the thing worth naming in a report.
private fun reasonFor(failure: Throwable): String = when (val cause = failure.cause ?: failure) {
    is HttpTimeoutException -> "timeout"
    else -> cause::class.java.name
}

private fun ClientRequest.asHttpRequest(fallback: Duration): HttpRequest =
    headers.fold(HttpRequest.newBuilder(URI.create(url))) { builder, (name, value) -> builder.header(name, value) }
        .timeout(timeout ?: fallback)
        .method(method.name, bodyPublisher())
        .build()

private fun ClientRequest.bodyPublisher(): HttpRequest.BodyPublisher = when (val carried = body) {
    is ClientRequest.Body.Empty -> HttpRequest.BodyPublishers.noBody()
    is ClientRequest.Body.Text -> HttpRequest.BodyPublishers.ofString(carried.content)
    is ClientRequest.Body.Streaming -> HttpRequest.BodyPublishers.ofInputStream(carried.open)
}

private fun HttpResponse<ByteArray>.headersAsPairs(): List<Pair<String, String>> =
    headers().map().entries.flatMap { (name, values) -> values.map { name to it } }

/** One path template, matched segment by segment against a URL's path. */
private class Template(val template: String) {

    private val segments = template.split("/")

    fun matches(path: String): Boolean {
        val actual = path.split("/")
        return actual.size == segments.size &&
            segments.zip(actual).all { (expected, seen) -> expected.isPlaceholder() || expected == seen }
    }
}

private fun String.isPlaceholder(): Boolean = startsWith("{") && endsWith("}")

/**
 * What a URL no template matched is filed under. A numeric segment is almost
 * always an id, and a row per id is how a report becomes unreadable.
 */
private fun String.generalised(): String =
    split("/").joinToString("/") { segment ->
        if (segment.isNotEmpty() &&
            segment.all(Char::isDigit)
        ) "{}" else segment
    }

private val SUCCESS = 200..299

private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
