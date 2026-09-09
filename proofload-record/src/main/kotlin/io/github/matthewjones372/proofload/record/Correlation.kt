package io.github.matthewjones372.proofload.record

/**
 * What one request's response produced and a later request used.
 *
 * The chain a hand-written scenario gets wrong, which is why it is here at all:
 * a transcription hard-codes the id that should have been captured, and nothing
 * fails — the run just measures a lighter experiment than the one somebody
 * meant.
 */
internal class Correlated(
    /** Captures to take, by the index of the request whose response produced them. */
    val producing: Map<Int, List<Capture>>,
    /** Paths with `{name}` where a capture fills them, by the index of the request that uses them. */
    val paths: Map<Int, String>,
    /** The same for bodies. */
    val bodies: Map<Int, String>,
    /** Values that might be a correlation and are not certain enough to be one. */
    val maybe: Map<Int, List<String>>,
)

/** `"key": "value"` in a body that is JSON, which is what a recording's responses mostly are. */
private val jsonField = Regex("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:\\s*\"([^\"\\\\]{4,200})\"")

/**
 * Finds every value a response produced and a later request used.
 *
 * The latest producer wins, which is what stops a value seen in two responses
 * being correlated to the wrong one: read backwards from the request that used
 * it, and the first response that carries it is the one that put it there.
 */
internal fun correlate(recorded: List<Recorded>): Correlated {
    val produced = recorded.mapIndexed { at, one -> at to one.produced() }.toMap()
    val producing = mutableMapOf<Int, MutableList<Capture>>()
    val paths = mutableMapOf<Int, String>()
    val bodies = mutableMapOf<Int, String>()
    val maybe = mutableMapOf<Int, MutableList<String>>()
    val named = mutableMapOf<String, String>()

    recorded.indices.forEach { using ->
        var path = recorded[using].target
        var body = recorded[using].body
        // Backwards, so the nearest response that carries the value is the one
        // credited with producing it.
        for (from in using - 1 downTo 0) {
            produced.getValue(from).forEach { (key, value) ->
                if (!path.uses(value) && !body.uses(value)) return@forEach
                if (recorded[from].echoes(value)) return@forEach
                if (!value.opaque()) {
                    maybe.getOrPut(using) { mutableListOf() }.add(value)
                    return@forEach
                }
                val name = named.getOrPut(value) {
                    val chosen = nameFor(key, recorded[from].path, named.values.toSet())
                    producing.getOrPut(from) { mutableListOf() }.add(Capture(chosen, key))
                    chosen
                }
                path = path.replace(value, "{$name}")
                body = body?.replace(value, "{$name}")
            }
        }
        if (path != recorded[using].target) paths[using] = path
        if (body != recorded[using].body) body?.let { bodies[using] = it }
    }

    return Correlated(producing, paths, bodies, maybe)
}

/** Every `"key": "value"` its answer carried, which is where a captured value comes from. */
private fun Recorded.produced(): List<Pair<String, String>> =
    answer?.body?.let { body -> jsonField.findAll(body).map { it.groupValues[1] to it.groupValues[2] }.toList() }
        .orEmpty()

/**
 * Whether this request already carried the value it is credited with producing.
 *
 * A service that echoes back what it was sent has produced nothing: capturing
 * that would generate a chain that reads as a correlation and is really the
 * scenario talking to itself.
 */
private fun Recorded.echoes(value: String): Boolean = target.uses(value) || body.uses(value)

private fun String?.uses(value: String): Boolean = this != null && contains(value)

/**
 * Whether a value is opaque enough to be a correlation rather than a
 * coincidence.
 *
 * A status, a count and a date all turn up in a response and again in a later
 * request without either having caused the other. The floor is length and
 * shape: long enough that a collision is unlikely, made of the characters an
 * identifier is made of, and carrying a digit, which is what separates an
 * opaque id from a word.
 */
private fun String.opaque(): Boolean =
    length >= OPAQUE && all { it.isLetterOrDigit() || it == '-' || it == '_' } && any { it.isDigit() }

private const val OPAQUE = 8

/**
 * What to call the session key: the producing path's own noun where the value
 * was found under a generic key, and the key itself otherwise.
 *
 * `{"id": "..."}` from `/orders` reads far better as `orderId` than as `id`,
 * and a scenario with three steps each capturing `id` reads as one key
 * overwritten three times.
 */
internal fun nameFor(key: String, producerPath: String, taken: Set<String>): String {
    val base = if (key.equals("id", ignoreCase = true)) "${producerPath.noun()}Id" else key.camelCased()
    if (base !in taken) return base
    return generateSequence(2) { it + 1 }.map { "$base$it" }.first { it !in taken }
}

/** The last segment that reads as a word, singular: `/v1/orders/42` is an order. */
private fun String.noun(): String {
    val word = split('/').lastOrNull { segment -> segment.isNotEmpty() && segment.all { it.isLetter() } }
        ?: return "captured"
    return word.singular().camelCased()
}

private fun String.singular(): String = when {
    endsWith("ss") -> this
    endsWith("s") && length > 1 -> dropLast(1)
    else -> this
}

private fun String.camelCased(): String = split('-', '_', '.')
    .filter { it.isNotEmpty() }
    .mapIndexed { at, part -> if (at == 0) part.replaceFirstChar { it.lowercase() } else part.capitalised() }
    .joinToString(separator = "")

private fun String.capitalised(): String = replaceFirstChar { it.uppercase() }
