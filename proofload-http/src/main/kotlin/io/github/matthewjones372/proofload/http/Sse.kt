package io.github.matthewjones372.proofload.http

/**
 * Where event streams are opened, and what is sent to open one. A value, so a
 * test against two feeds names two of these rather than setting a global that
 * the second one overwrites.
 */
class Sse internal constructor(
    private val baseUrl: String,
    private val traced: Boolean = false,
) {

    /** Trailing slash trimmed, so the base and the path agree about the one between them. */
    fun baseUrl(url: String): Sse = Sse(url.trimEnd('/'), traced)

    /**
     * Puts a W3C `traceparent` and a synthetic-traffic `baggage` entry on the
     * request that opens a stream, as `Http.traced` does on a request.
     *
     * One id for the whole stream rather than one per event: the trace is of
     * the call that opened it, and there is no second request to correlate.
     */
    fun traced(): Sse = Sse(baseUrl, traced = true)

    fun at(path: String): SseTarget = SseTarget(baseUrl + path, traced)
}

/** One feed to open, as a value two scenarios can share. */
class SseTarget internal constructor(internal val url: String, internal val traced: Boolean)

/** No base URL of its own: `sse.baseUrl(...)` gives one, or a path can be absolute. */
val sse: Sse = Sse("")
