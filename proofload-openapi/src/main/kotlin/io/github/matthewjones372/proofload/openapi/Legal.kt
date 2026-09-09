package io.github.matthewjones372.proofload.openapi

import kotlin.math.max
import kotlin.math.min

/**
 * The same, from a schema as a document states it.
 *
 * One generator for both directions. A `minimum` written in an OpenAPI document
 * and a `between(1, 100)` on a Pelican input are the same constraint, and two
 * generators would be two answers to it.
 */
fun legalFor(facets: Map<String, Any?>, type: String? = null, example: Any? = null, seed: Long): String {
    (facets["enum"] as? List<*>)?.firstOrNull()?.let { return it.toString() }

    val shown = example?.toString() ?: facets["example"]?.toString()
    shown?.takeIf { it.isNotBlank() && it.satisfies(facets) }?.let { return it }

    val named = type ?: facets["type"]?.toString()
    return when (named) {
        "integer", "number" -> numberIn(facets, seed)
        "boolean" -> "true"
        else -> textIn(facets, seed)
    }
}

private fun numberIn(facets: Map<String, Any?>, seed: Long): String {
    val low = facets.number("minimum") ?: facets.number("exclusiveMinimum")?.plus(1)
    val high = facets.number("maximum") ?: facets.number("exclusiveMaximum")?.minus(1)

    val floor = low ?: 1L
    val ceiling = high ?: (floor + SPREAD)
    // Inside the range and stable for a seed: the same contract yields the same
    // plan twice, which is what makes a generated file reviewable.
    return (floor + Math.floorMod(seed, max(1L, ceiling - floor + 1))).coerceIn(floor, ceiling).toString()
}

private fun textIn(facets: Map<String, Any?>, seed: Long): String {
    val shortest = facets.number("minLength")?.toInt() ?: 1
    val longest = facets.number("maxLength")?.toInt() ?: max(shortest, DEFAULT_TEXT)
    val length = min(max(shortest, DEFAULT_TEXT), longest)

    val alphabet = "abcdefghijklmnopqrstuvwxyz"
    return (0 until length)
        .map { alphabet[Math.floorMod(seed + it, alphabet.length.toLong()).toInt()] }
        .joinToString("")
}

/** Whether the author's own example is still inside what the facets allow. */
private fun String.satisfies(facets: Map<String, Any?>): Boolean =
    withinBounds(facets) && withinLengths(facets) && matchesPattern(facets)

private fun String.withinBounds(facets: Map<String, Any?>): Boolean {
    val number = toLongOrNull() ?: return true
    val low = facets.number("minimum")
    val high = facets.number("maximum")
    return (low == null || number >= low) && (high == null || number <= high)
}

private fun String.withinLengths(facets: Map<String, Any?>): Boolean {
    val shortest = facets.number("minLength")?.toInt()
    val longest = facets.number("maxLength")?.toInt()
    return (shortest == null || length >= shortest) && (longest == null || length <= longest)
}

private fun String.matchesPattern(facets: Map<String, Any?>): Boolean =
    (facets["pattern"] as? String)?.let { Regex(it).containsMatchIn(this) } ?: true

private fun Map<String, Any?>.number(key: String): Long? = when (val at = this[key]) {
    is Number -> at.toLong()
    is String -> at.toLongOrNull()
    else -> null
}

/** How wide a range to invent where the schema names no ceiling. */
private const val SPREAD = 1_000L
private const val DEFAULT_TEXT = 8
