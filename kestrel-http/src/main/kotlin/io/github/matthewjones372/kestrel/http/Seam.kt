package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.Reason
import java.net.URI
import kotlin.time.Duration

/**
 * One request, in this module's own terms rather than the JDK's.
 *
 * Everything a sender needs and nothing about how it sends: no builder, no
 * client, no redirect policy. What travels across the seam is a value, so a
 * transport built on another client does not have to accept a
 * `java.net.http.HttpRequest` and unpick it.
 */
data class Request(
    val method: String,
    val uri: URI,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeout: Duration,
)

/**
 * What came back, or why nothing did.
 *
 * A failure is a value rather than a throw, for the reason every failure in
 * this repository is: an engine measures a failure by reading a result, so a
 * target's bad day must not arrive by the same route as a bug in the
 * generator.
 */
sealed interface Exchange {

    data class Answered(val response: Response) : Exchange

    data class Failed(val reason: Reason) : Exchange
}

/**
 * How a request is actually sent.
 *
 * One method, because a round trip is one thing: a wider interface would
 * describe the JDK client's internals rather than what a sender is. What stays
 * *above* this seam is everything that decides what a run measures — the
 * redirect walk and `TooManyRedirects`, the per-user cookie jar, the
 * `traceparent`, the status and check reasons — so a transport cannot change
 * what a number means, only how fast it can be got.
 *
 * A transport owes two failures and no more: [TimedOut] where the target did
 * not answer in time, and `Threw(class)` for anything else. It carries no
 * duration: service time is measured by the engine around the step and
 * response time from the departure the profile promised, and a third clock
 * here would be one to reconcile.
 *
 * No `StepScope` is handed over. A transport holding the scope could write to
 * the user's session, and first-reason-wins would be a stranger's to keep.
 */
fun interface Transport {

    fun exchange(request: Request): Exchange
}
