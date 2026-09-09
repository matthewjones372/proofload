package io.github.matthewjones372.proofload.record

import kotlin.time.Duration

/** One step of the draft before it is redacted: what was recorded, and what it stood for. */
internal class Collapsed(
    val recorded: Recorded,
    val path: String,
    val body: String?,
    val stoodFor: Int,
    val captures: List<Capture>,
    val maybe: List<String>,
    val after: Duration,
)

/**
 * Runs of one path differing only by a segment become one step.
 *
 * Forty `GET /products/17`, `/products/18`, `/products/19` are one endpoint
 * under load, and forty rows in a report is forty rows nobody reads. What is
 * kept is the count, because a step that stood for forty is a different finding
 * from one that stood for one.
 *
 * A segment a capture already filled is left alone: `{orderId}` is a chain
 * somebody can run, and `{id}` over the top of it is the same segment named
 * twice.
 */
internal fun collapse(recorded: List<Recorded>, correlated: Correlated): List<Collapsed> {
    val each = recorded.mapIndexed { at, one ->
        Collapsed(
            recorded = one,
            path = correlated.paths[at] ?: one.target,
            body = correlated.bodies[at] ?: one.body,
            stoodFor = 1,
            captures = correlated.producing[at].orEmpty(),
            maybe = correlated.maybe[at].orEmpty(),
            after = if (at == 0) Duration.ZERO else one.at - recorded[at - 1].at,
        )
    }

    // Grouped by what the path looks like with its variable segments named, so
    // requests that are the same endpoint land together wherever they appear.
    val grouped = each.groupBy { it.recorded.method to it.path.templated() }
    val seen = mutableSetOf<Pair<String, String>>()
    return each.mapNotNull { one ->
        val key = one.recorded.method to one.path.templated()
        val group = grouped.getValue(key)
        when {
            group.size == 1 -> one

            !seen.add(key) -> null

            // The first of the run carries the group: its captures and its
            // body are a real request's, and the path is the shape they shared.
            else -> Collapsed(
                recorded = one.recorded,
                path = one.path.templated(),
                body = one.body,
                stoodFor = group.size,
                captures = one.captures,
                maybe = one.maybe,
                after = one.after,
            )
        }
    }
}

/** Numeric and uuid-shaped segments named, and everything a capture already named left alone. */
private fun String.templated(): String = split('/').joinToString("/") { segment ->
    if (segment.variable()) "{id}" else segment
}

private fun String.variable(): Boolean = when {
    startsWith("{") -> false
    isEmpty() -> false
    all { it.isDigit() } -> true
    else -> uuidShaped()
}

/** 8-4-4-4-12 hexadecimal, which is what a generated identifier in a path nearly always is. */
private fun String.uuidShaped(): Boolean {
    val parts = split('-')
    if (parts.map { it.length } != listOf(UUID_A, UUID_B, UUID_B, UUID_B, UUID_C)) return false
    return all { it == '-' || it.isDigit() || it.lowercaseChar() in 'a'..'f' }
}

private const val UUID_A = 8
private const val UUID_B = 4
private const val UUID_C = 12
