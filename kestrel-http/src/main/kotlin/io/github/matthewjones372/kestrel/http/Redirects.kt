package io.github.matthewjones372.kestrel.http

import java.net.URI

private const val MOVED_PERMANENTLY = 301

private const val FOUND = 302

private const val SEE_OTHER = 303

private const val TEMPORARY_REDIRECT = 307

private const val PERMANENT_REDIRECT = 308

private const val LOCATION = "location"

private const val GET = "GET"

/** The statuses that name somewhere else to go: 304 is a 3xx that does not. */
private val redirects = setOf(MOVED_PERMANENTLY, FOUND, SEE_OTHER, TEMPORARY_REDIRECT, PERMANENT_REDIRECT)

/** The two that promise the method and its body survive the hop. */
private val methodKept = setOf(TEMPORARY_REDIRECT, PERMANENT_REDIRECT)

/** One request in a chain: where it goes and what it is sent as. */
internal data class Hop(val uri: URI, val method: String, val body: String?)

/**
 * The hop this response asks for, or null when it asks for none.
 *
 * `Location` is resolved against [from] because it is usually a path, and a hop
 * to `/account` has to keep the host the request before it was sent to.
 */
internal fun Response.hopFrom(from: Hop): Hop? {
    val target = if (status in redirects) header(LOCATION)?.let(from.uri::resolve) else null
    // A 301, 302 or 303 is followed as a bodyless GET, which is what a browser
    // does and what a sign-in answering 302 means; 307 and 308 were added to
    // say the opposite, so they carry the method and body through.
    return when {
        target == null -> null
        status in methodKept -> Hop(target, from.method, from.body)
        else -> Hop(target, GET, null)
    }
}
