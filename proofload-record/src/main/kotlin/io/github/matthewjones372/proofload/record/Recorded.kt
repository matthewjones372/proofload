package io.github.matthewjones372.proofload.record

import java.net.URI
import kotlin.time.Duration

/** One header as a recording carried it: names repeat, so this is not a map. */
data class Header(val name: String, val value: String)

/** What the target answered, where it answered at all. */
data class Answer(val status: Int, val headers: List<Header>, val body: String?)

/**
 * One request a recording captured.
 *
 * The recording's own shape, not a `Request`: this is read before a run rather
 * than sent during one, and nothing here goes near the timed path. [answer] is
 * null where the entry has no response — a request that was cut off, which a
 * browser records as a status of zero and a proxy records by leaving it out.
 */
data class Recorded(
    val method: String,
    val url: String,
    val headers: List<Header>,
    val body: String?,
    val answer: Answer?,
    /** How long after the recording's first request this one left. */
    val at: Duration,
) {

    private val uri: URI get() = URI.create(url)

    /** The path as recorded, and `/` for a URL that named none. */
    val path: String get() = uri.rawPath.orEmpty().ifEmpty { "/" }

    /** Scheme and authority, which is what a base URL is made of. */
    val origin: String get() = "${uri.scheme}://${uri.authority}"

    /** The query as recorded, kept whole: a run that drops it asks a different question. */
    val query: String? get() = uri.rawQuery

    /**
     * The path and the query together, which is what a capture fills and what a
     * step sends.
     *
     * The query is part of the request: a run that drops it asks a different
     * question, and a value a recording carried in one is as much a correlation
     * as one it carried in a segment.
     */
    val target: String get() = query?.let { "$path?$it" } ?: path

    /** What a step would be named, before anything is collapsed or templated. */
    val named: String get() = "$method $path"
}
