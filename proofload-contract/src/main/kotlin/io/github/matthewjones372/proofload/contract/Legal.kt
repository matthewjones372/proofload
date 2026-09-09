package io.github.matthewjones372.proofload.contract

import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonNum
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.PathParam
import io.github.matthewjones372.proofload.openapi.legalFor

/**
 * A value the contract already says is acceptable.
 *
 * The constraints on an input are the same rule that refuses a bad request and
 * the `minimum`/`maximum` in the schema, so a value drawn inside them exercises
 * the endpoint rather than its validation. A generator that guessed would
 * measure a service rejecting it.
 *
 * One value per parameter, not one per user. A generated plan is a smoke at one
 * a second; per-user variety is what `proofload emit` and a feeder are for, and
 * saying so is better than a file format that half-expresses it.
 */
internal fun PathParam<*>.legalValue(seed: Long): String {
    val facets = codec.schemaFacets.fields.mapValues { (_, value) -> value.plainly() }

    codec.enumValues?.firstOrNull()?.let { return it.toString() }
    return legalFor(
        facets = facets,
        type = codec.openApiType,
        // The contract's own example, where it gives one: what the author chose
        // reads better in a report than anything computed here.
        example = codec.example,
        seed = seed,
    )
}

private fun JsonValue.plainly(): Any = when (this) {
    is JsonStr -> component1()
    is JsonNum -> component1()
    is JsonArr -> component1().map { it.plainly() }
    else -> toString()
}
