package io.github.matthewjones372.kestrel.contract

import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonNum
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.PathParam
import kotlin.math.max
import kotlin.math.min

/**
 * A value the contract already says is acceptable.
 *
 * The constraints on an input are the same rule that refuses a bad request and
 * the `minimum`/`maximum` in the schema, so a value drawn inside them exercises
 * the endpoint rather than its validation. A generator that guessed would
 * measure a service rejecting it.
 *
 * One value per parameter, not one per user. A generated plan is a smoke at one
 * a second; per-user variety is what `kestrel emit` and a feeder are for, and
 * saying so is better than a file format that half-expresses it.
 */
internal fun PathParam<*>.legalValue(seed: Long): String {
    val facets = codec.schemaFacets.fields

    codec.enumValues?.firstOrNull()?.let { return it.toString() }
    facets["enum"]?.let { declared ->
        (declared as? JsonArr)?.component1()?.firstOrNull()?.let { return it.plainly() }
    }

    // The contract's own example, where it gives one and the facets do not
    // contradict it: what the author chose reads better in a report than
    // anything computed here.
    codec.example?.takeIf { it.isNotBlank() && it.satisfies(facets) }?.let { return it }

    return when (codec.openApiType) {
        "integer", "number" -> numberIn(facets, seed)
        "boolean" -> "true"
        else -> textIn(facets, seed)
    }
}

private fun numberIn(facets: Map<String, JsonValue>, seed: Long): String {
    val low = facets.number("minimum") ?: facets.number("exclusiveMinimum")?.plus(1)
    val high = facets.number("maximum") ?: facets.number("exclusiveMaximum")?.minus(1)

    val floor = low ?: 1L
    val ceiling = high ?: (floor + SPREAD)
    // Inside the range and stable for a seed: the same contract yields the same
    // plan twice, which is what makes a generated file reviewable.
    return (floor + Math.floorMod(seed, max(1L, ceiling - floor + 1))).coerceIn(floor, ceiling).toString()
}

private fun textIn(facets: Map<String, JsonValue>, seed: Long): String {
    val shortest = facets.number("minLength")?.toInt() ?: 1
    val longest = facets.number("maxLength")?.toInt() ?: max(shortest, DEFAULT_TEXT)
    val length = min(max(shortest, DEFAULT_TEXT), longest)

    val alphabet = "abcdefghijklmnopqrstuvwxyz"
    return (0 until length)
        .map { alphabet[Math.floorMod(seed + it, alphabet.length.toLong()).toInt()] }
        .joinToString("")
}

/** Whether the author's own example is still inside what the facets allow. */
private fun String.satisfies(facets: Map<String, JsonValue>): Boolean =
    withinBounds(facets) && withinLengths(facets) && matchesPattern(facets)

private fun String.withinBounds(facets: Map<String, JsonValue>): Boolean {
    val number = toLongOrNull() ?: return true
    val low = facets.number("minimum")
    val high = facets.number("maximum")
    return (low == null || number >= low) && (high == null || number <= high)
}

private fun String.withinLengths(facets: Map<String, JsonValue>): Boolean {
    val shortest = facets.number("minLength")?.toInt()
    val longest = facets.number("maxLength")?.toInt()
    return (shortest == null || length >= shortest) && (longest == null || length <= longest)
}

private fun String.matchesPattern(facets: Map<String, JsonValue>): Boolean =
    facets["pattern"].let { it !is JsonStr || Regex(it.component1()).containsMatchIn(this) }

private fun Map<String, JsonValue>.number(key: String): Long? = (this[key] as? JsonNum)?.component1()?.toLong()

private fun JsonValue.plainly(): String = when (this) {
    is JsonStr -> component1()
    is JsonNum -> component1().toString()
    else -> toString()
}

/** How wide a range to invent where the contract names no ceiling. */
private const val SPREAD = 1_000L
private const val DEFAULT_TEXT = 8
