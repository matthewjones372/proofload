package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.StepScope
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

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
 * Sends [request], turning the transport's exceptions into a failure on [scope]
 * and returning null. Nothing throws out of here: an engine reads the step's
 * result to measure a failure, so a target's bad day must not arrive by the
 * same route as a bug in the generator.
 */
internal fun exchange(request: HttpRequest, scope: StepScope): Response? =
    try {
        Response.of(sharedClient.send(request, HttpResponse.BodyHandlers.ofString()))
    } catch (failure: IOException) {
        scope.fail(failure.reason())
        null
    } catch (interrupted: InterruptedException) {
        // The engine is stopping this user; leave the flag set for whatever
        // checks it next, and record the step as the failure it is.
        Thread.currentThread().interrupt()
        scope.fail(interrupted.className())
        null
    }

/**
 * The class name, not the message: `ConnectException` is one row in a report,
 * while its message carries a host and a port and would be thousands. A timeout
 * is named instead of classed because it is the one transport failure a reader
 * acts on differently — the target was reachable and did not answer in time.
 */
private fun IOException.reason(): String = if (this is HttpTimeoutException) "timeout" else className()

private fun Throwable.className(): String = this::class.simpleName ?: javaClass.name
