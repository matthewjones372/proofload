package io.github.matthewjones372.kestrel.openapi

import io.github.matthewjones372.kestrel.plan.DeclaredDraw

/**
 * The generator a parameter's schema describes, or null where it describes no
 * space to draw from.
 *
 * Only where the contract says what the space is. An `enum` lists it exactly,
 * and `minimum` with `maximum` bounds it; anything else — a bare string, an
 * integer with no ceiling — is left to be substituted as before. Inventing a
 * range the document never stated would be inventing a cardinality, which is
 * the number 0096 says decides a p99.
 */
internal fun drawFor(facets: Map<String, Any?>): DeclaredDraw? =
    facets.listed() ?: facets.bounded()

/** Every value an `enum` names, which is the space stated exactly. */
private fun Map<String, Any?>.listed(): DeclaredDraw? =
    (this["enum"] as? List<*>)
        ?.mapNotNull { it?.toString() }
        ?.takeIf { it.isNotEmpty() }
        ?.let { DeclaredDraw.OneOf(it) }

/** The range a number states at both ends. One end alone bounds nothing. */
private fun Map<String, Any?>.bounded(): DeclaredDraw? {
    if (this["type"]?.toString() !in NUMBERS) return null

    val low = bound("minimum") ?: bound("exclusiveMinimum")?.plus(1)
    val high = bound("maximum") ?: bound("exclusiveMaximum")?.minus(1)

    return if (low == null || high == null || high < low) null
    else DeclaredDraw.Uniform(keys = high - low + 1, from = low)
}

private fun Map<String, Any?>.bound(name: String): Long? = (this[name] as? Number)?.toLong()

private val NUMBERS = setOf("integer", "number")
