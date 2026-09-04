package io.github.matthewjones372.kestrel.arbs

import java.util.UUID
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * A rank in `0 until keys`, drawn Zipf: rank 0 is the busiest key, and the
 * traffic to a rank falls away as `rank^-skew`.
 *
 * This is the parameter that moves a p99. A thousand keys cycled all run sit
 * in every cache the target has, and keys drawn uniformly over a large space
 * miss all of them; real traffic does neither, and [skew] is where a caller
 * says so. Around 1.0 is the shape most catalogues and most user tables have;
 * below it flattens, above it concentrates.
 *
 * A rank rather than a key, so nothing here has guessed the target's id
 * scheme. `map` turns it into whatever the caller's keyspace is.
 */
fun zipf(keys: Long, skew: Double, seed: Long = 0L): Arb<Long> {
    require(keys > 0) { "a keyspace has at least one key, but zipf was given $keys" }
    require(skew > 0.0) { "zipf needs a skew above zero; a keyspace nobody favours is uniform($keys)" }
    return Zipf(keys, skew, Shape("zipf(keys=$keys, skew=$skew)", seed))
}

/**
 * A rank in `0 until keys`, every key as likely as every other.
 *
 * The cardinality without the skew: right where a keyspace really is flat, and
 * a cache-miss machine where it is not.
 */
fun uniform(keys: Long, seed: Long = 0L): Arb<Long> {
    require(keys > 0) { "a keyspace has at least one key, but uniform was given $keys" }
    return Uniform(keys, Shape("uniform(keys=$keys)", seed))
}

/** [count] decimal digits, zero-padded, so the keyspace is the `10^count` the count names. */
fun digits(count: Int, seed: Long = 0L): Arb<String> {
    require(count in 1..MOST_DIGITS_IN_A_LONG) { "digits holds 1 to $MOST_DIGITS_IN_A_LONG digits, not $count" }
    return Uniform(TEN.pow(count).toLong(), Shape("digits($count)", seed))
        .map { it.toString().padStart(count, '0') }
}

/** A UUID per user, the same one every time that user is asked about. */
fun uuids(seed: Long = 0L): Arb<UUID> = Uuids(Shape("uuids()", seed))

/**
 * One of [options], drawn in proportion to the weight beside it.
 *
 * The weights are relative, so three percentages and `90.0`, `9.0`, `1.0` mean
 * the same thing. A weight of zero is never drawn, which turns an option off
 * without deleting it from a list somebody is reading as the traffic mix.
 */
fun <T> weighted(options: List<Pair<T, Double>>, seed: Long = 0L): Arb<T> {
    require(options.isNotEmpty()) { "weighted needs something to choose from, but was given an empty list" }
    require(options.all { it.second >= 0.0 }) { "a weight cannot be negative: $options" }
    require(options.sumOf { it.second } > 0.0) { "every weight was zero, so nothing could ever be drawn" }
    return Weighted(options, Shape("weighted(${options.size} options)", seed))
}

fun <T> weighted(vararg options: Pair<T, Double>, seed: Long = 0L): Arb<T> = weighted(options.toList(), seed)

private const val TEN = 10.0

/** `Long.MAX_VALUE` has nineteen digits and the nineteen-digit keyspace does not fit inside it. */
private const val MOST_DIGITS_IN_A_LONG = 18

private class Uniform(private val keys: Long, override val shape: Shape) : Arb<Long> {

    private val salt = salt(shape.seed, keys)

    override fun at(user: Long): Long = bounded(draw(user, salt), keys)
}

private class Uuids(override val shape: Shape) : Arb<UUID> {

    private val salt = salt(shape.seed, 0L)

    override fun at(user: Long): UUID {
        val high = draw(user, salt)
        val low = draw(user, salt + 1)
        // Version 4 in the high half and the variant bits in the low one, so
        // what comes out is a UUID a target will accept rather than sixteen
        // random bytes wearing the punctuation.
        return UUID((high and VERSION_FIELD.inv()) or VERSION_4, (low and VARIANT_FIELD.inv()) or VARIANT_RFC)
    }

    private companion object {
        const val VERSION_FIELD = 0xF000L
        const val VERSION_4 = 0x4000L
        const val VARIANT_FIELD = -0x4000000000000000L
        const val VARIANT_RFC = Long.MIN_VALUE
    }
}

private class Weighted<T>(private val options: List<Pair<T, Double>>, override val shape: Shape) : Arb<T> {

    private val salt = salt(shape.seed, options.size.toLong())

    /** Running totals, worked out once, so a draw is a scan rather than a sum. */
    private val cumulative = options
        .runningFold(0.0) { running, option -> running + option.second }
        .drop(1)
        .toDoubleArray()

    private val total = cumulative.last()

    override fun at(user: Long): T {
        val target = unitInterval(draw(user, salt)) * total
        // A scan rather than a binary search: a traffic mix is a handful of
        // options. No total above the target means the rounding in the running
        // sum left it a hair short, so the last option is the one meant.
        val found = cumulative.indexOfFirst { target < it }
        return options[if (found < 0) options.lastIndex else found].first
    }
}

/**
 * Rejection-inversion sampling (Hörmann and Derflinger, 1996): the inverse of
 * the integral of `rank^-skew`, with the rounding to a whole rank rejected
 * where it would bias the result.
 *
 * A cumulative table would be a million doubles for a million keys, held per
 * generator and binary-searched per draw. This is a fixed amount of arithmetic
 * and no table, which is what lets the keyspace be as large as a real one.
 *
 * A rejected draw is retried against the next salt rather than against a fresh
 * number, so the generator stays a pure function of the user's number.
 */
private class Zipf(private val keys: Long, private val skew: Double, override val shape: Shape) : Arb<Long> {

    private val salt = salt(shape.seed, keys + skew.toRawBits())

    private val atFirstKey = hIntegral(ABOVE_FIRST) - 1.0

    private val atLastKey = hIntegral(keys + HALF)

    /** How far below its own midpoint a draw may land and still be that rank without being checked. */
    private val alwaysAccepted = SECOND - hIntegralInverse(hIntegral(ABOVE_SECOND) - h(SECOND))

    override fun at(user: Long): Long {
        var attempt = 0L
        while (true) {
            val u = atLastKey + unitInterval(draw(user, salt + attempt)) * (atFirstKey - atLastKey)
            val x = hIntegralInverse(u)
            val rank = x.roundToLong().coerceIn(1L, keys)
            if (rank - x <= alwaysAccepted || u >= hIntegral(rank + HALF) - h(rank.toDouble())) {
                return rank - 1
            }
            attempt++
        }
    }

    private fun h(x: Double): Double = x.pow(-skew)

    /** The integral of [h], written so a skew of exactly 1 is the logarithm without a branch for it. */
    private fun hIntegral(x: Double): Double {
        val logX = ln(x)
        return logX * expRatio((1.0 - skew) * logX)
    }

    private fun hIntegralInverse(y: Double): Double = exp(logRatio((1.0 - skew) * y) * y)

    private companion object {
        const val HALF = 0.5
        const val ABOVE_FIRST = 1.5
        const val SECOND = 2.0
        const val ABOVE_SECOND = 2.5
    }
}

/** `(e^t - 1) / t`, and its limit of 1 at zero: what keeps a skew near 1 from cancelling itself away. */
private fun expRatio(t: Double): Double = if (t == 0.0) 1.0 else expm1(t) / t

/** `ln(1 + t) / t`, and its limit of 1 at zero, for the same reason. */
private fun logRatio(t: Double): Double = if (t == 0.0) 1.0 else ln1p(t) / t
