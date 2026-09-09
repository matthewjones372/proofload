package io.github.matthewjones372.proofload.arbs

import io.github.matthewjones372.proofload.Shape

/**
 * A value worked out from a virtual user's number.
 *
 * Not kotest's `Arb`, which leans towards edge cases because it is hunting
 * bugs. These lean towards traffic: the parameter that moves a p99 is how many
 * distinct keys there are and how unevenly they are asked for.
 *
 * [at] rather than `next()`, so a run replays, a failure at user 8,412 is
 * re-derivable, and fifty thousand virtual threads share no source to contend
 * on. Two generators built the same way draw the same values; pass a different
 * [Shape.seed] to separate them.
 */
interface Arb<out T> {

    infix fun at(user: Long): T

    val shape: Shape
}

/** The same draw in the caller's own keyspace: a rank becomes an id, an index becomes a name. */
fun <T, R> Arb<T>.map(transform: (T) -> R): Arb<R> = Mapped(this, transform)

/**
 * One of [values], drawn for the user. Uniform over the list, which is the
 * right shape for a handful of product names and the wrong one for a keyspace,
 * which is what [zipf] is for.
 */
fun <T> oneOf(values: List<T>, seed: Long = 0L): Arb<T> {
    require(values.isNotEmpty()) { "oneOf needs something to choose from, but was given an empty list" }
    return OneOf(values, Shape("oneOf(${values.size} values)", seed))
}

fun <T> oneOf(vararg values: T, seed: Long = 0L): Arb<T> = oneOf(values.toList(), seed)

private class Mapped<T, R>(private val source: Arb<T>, private val transform: (T) -> R) : Arb<R> {

    override fun at(user: Long): R = transform(source at user)

    // The source's, unchanged: mapping renames a key and leaves the
    // cardinality and the skew the run was measured under where they were.
    override val shape: Shape get() = source.shape
}

private class OneOf<T>(private val values: List<T>, override val shape: Shape) : Arb<T> {

    private val salt = salt(shape.seed, values.size.toLong())

    override fun at(user: Long): T = values[bounded(draw(user, salt), values.size.toLong()).toInt()]
}
