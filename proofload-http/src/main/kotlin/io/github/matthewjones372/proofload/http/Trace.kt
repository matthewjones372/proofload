package io.github.matthewjones372.proofload.http

import io.github.matthewjones372.proofload.SYNTHETIC
import io.github.matthewjones372.proofload.StepScope
import io.github.matthewjones372.proofload.Traceparent

private const val TRACEPARENT = "traceparent"

private const val BAGGAGE = "baggage"

/**
 * Adds the W3C trace headers, or nothing at all when the client is untraced.
 *
 * Applied before the request's own headers, so a scenario that sets either name
 * itself is the one a reader sees first on the wire.
 */
internal fun tracing(traced: Boolean, scope: StepScope): Map<String, String> {
    if (!traced) return emptyMap()
    val traceparent = Traceparent.next()
    // Told to the scope as well as sent: a percentile with no id beside it
    // leaves a reader to search a tracing backend by timestamp, which is the
    // search this exists to replace.
    scope.traced(Traceparent.idIn(traceparent))
    return mapOf(TRACEPARENT to traceparent, BAGGAGE to SYNTHETIC)
}
