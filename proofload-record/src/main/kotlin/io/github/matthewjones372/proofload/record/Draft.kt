package io.github.matthewjones372.proofload.record

import kotlin.time.Duration

/** A value one step's response produced and a later step used. */
data class Capture(
    /** The session key the generated source declares. */
    val name: String,
    /** The JSON key it was found under, which is what the extractor looks for. */
    val key: String,
)

/** One step of the draft: what to send, what came back, and what to take out of it. */
data class DraftStep(
    val method: String,
    /** Templated where a capture fills it, and relative to the base URL where it belongs to one. */
    val path: String,
    /** True where the path is a whole URL, because it was not the origin the base URL names. */
    val absolute: Boolean,
    val headers: List<Header>,
    /** The header names dropped as credentials, each of which leaves a `TODO` in the output. */
    val dropped: List<String>,
    /**
     * The query parameter names dropped as credentials.
     *
     * Apart from [dropped] because a parameter cannot hold a `TODO` the way a
     * header can: the output says one was taken and does not invent a header
     * that was never sent.
     */
    val droppedParameters: List<String> = emptyList(),
    val body: String?,
    val expecting: Int,
    val captures: List<Capture>,
    /** How many requests this step stood for, which is one unless a run of them collapsed. */
    val stoodFor: Int,
    /** The gap before this request, emitted as a commented-out `pause`. */
    val after: Duration,
    /** Values that might be a correlation and are not certain enough to be one. */
    val maybe: List<String>,
    /** Whether the recording got no answer to this request, so nothing said what to expect. */
    val unanswered: Boolean,
)

/**
 * A recording read into the shape the generator writes out: a base URL, the
 * session keys the chain needs, and one step per request.
 *
 * A value between the parser and the emitter so each is testable on its own —
 * and so the decisions that make a recording into a scenario (which origin is
 * the base, which value is a capture, which forty requests are one step) are in
 * one place a reader can argue with.
 */
data class Draft(
    val baseUrl: String?,
    /** Every other origin the recording touched, which the output names in a comment. */
    val elsewhere: List<String>,
    val steps: List<DraftStep>,
) {

    /** Every session key the output declares, in the order they are first captured. */
    val captures: List<Capture> get() = steps.flatMap { it.captures }
}

/**
 * Reads [recorded] into a draft: filtered, correlated, collapsed and redacted,
 * in that order.
 *
 * The order is the whole of the design. Correlation reads the values a
 * recording actually carried, so it runs before anything is dropped; collapsing
 * must not re-template a segment a capture already filled; and redaction is
 * last so nothing downstream can put a credential back.
 */
fun draft(
    recorded: List<Recorded>,
    include: Regex? = null,
    exclude: Regex? = null,
): Draft {
    val kept = recorded.filter { it.wanted(include, exclude) }
    val origins = kept.groupingBy { it.origin }.eachCount()
    val base = origins.maxByOrNull { it.value }?.key
    val correlated = correlate(kept)
    val collapsed = collapse(kept, correlated)

    return Draft(
        baseUrl = base,
        elsewhere = origins.keys.filterNot { it == base }.sorted(),
        steps = collapsed.map { it.asStep(base) },
    )
}

/**
 * The step this stood for, with its credentials dropped.
 *
 * Redaction is last on purpose: nothing downstream of here can put a credential
 * back, and what is left is a list of names the output turns into a `TODO` that
 * stops the generated file running until somebody has decided what goes there.
 */
private fun Collapsed.asStep(base: String?): DraftStep {
    val (kept, dropped) = recorded.headers.partition { !it.isCredential() }
    val mine = base != null && recorded.origin == base
    // A token rides in the query string as readily as in a header, and the
    // path becomes the step's name — so one left here reaches every report and
    // every baseline the run writes, not only this file.
    val (safePath, droppedParameters) = path.withoutCredentialParameters()
    return DraftStep(
        method = recorded.method,
        path = if (mine) safePath else recorded.origin + safePath,
        absolute = !mine,
        headers = kept.filterNot { it.name.lowercase() in uninteresting },
        dropped = dropped.map { it.name }.distinct(),
        droppedParameters = droppedParameters,
        body = body?.withoutSecrets(),
        // A request that got no answer said nothing about what to expect, so the
        // step asks for the default and the output says the recording did not
        // know. `expecting(0)` would be a step that can only fail.
        expecting = recorded.answer?.status ?: DEFAULT_EXPECTED,
        unanswered = recorded.answer == null,
        captures = captures,
        stoodFor = stoodFor,
        after = after,
        maybe = maybe,
    )
}

private const val DEFAULT_EXPECTED = 200

/**
 * Headers a client sets for itself.
 *
 * Written into a scenario they are a request nobody wrote: the JDK client sets
 * its own `host`, negotiates its own encoding, and refuses several of these
 * outright — a generated file that sent them would not run at all.
 */
private val uninteresting = setOf(
    "host",
    "connection",
    "content-length",
    "accept-encoding",
    "upgrade-insecure-requests",
    "sec-fetch-dest",
    "sec-fetch-mode",
    "sec-fetch-site",
    "sec-fetch-user",
    "sec-ch-ua",
    "sec-ch-ua-mobile",
    "sec-ch-ua-platform",
)

/**
 * Whether this request is one somebody meant to make.
 *
 * A page load is forty requests to a CDN and one to the API, and including them
 * measures somebody else's cache. The default is the extensions a browser
 * fetches without being asked; `include` and `exclude` are the caller's own
 * rules and are applied to the path.
 */
internal fun Recorded.wanted(include: Regex?, exclude: Regex?): Boolean {
    if (include != null && !include.containsMatchIn(path)) return false
    if (exclude != null && exclude.containsMatchIn(path)) return false
    return path.substringAfterLast('.', "").lowercase() !in staticAssets
}

private val staticAssets = setOf(
    "css", "js", "mjs", "map",
    "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "avif", "bmp",
    "woff", "woff2", "ttf", "otf", "eot",
    "mp4", "webm", "ogg", "mp3", "wav",
    "pdf", "zip", "gz",
)
