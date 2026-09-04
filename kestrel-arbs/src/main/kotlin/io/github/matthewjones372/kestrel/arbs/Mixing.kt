package io.github.matthewjones372.kestrel.arbs

/** 2^64 over the golden ratio: the multiplier that spreads consecutive user numbers furthest apart. */
private const val GOLDEN = -0x61c8864680b583ebL

// SplitMix64's finalizing constants, unchanged from the reference
// implementation. They were searched for as a set, so altering one costs the
// avalanche the other two were chosen against.
private const val FIRST_SHIFT = 30
private const val FIRST_ROUND = -0x40a7b892e31b1a47L
private const val SECOND_SHIFT = 27
private const val SECOND_ROUND = -0x6b2fb644ecceee15L
private const val LAST_SHIFT = 31

/** An avalanche wide enough that two user numbers one apart share no bits. */
private fun avalanche(value: Long): Long {
    val once = (value xor (value ushr FIRST_SHIFT)) * FIRST_ROUND
    val twice = (once xor (once ushr SECOND_SHIFT)) * SECOND_ROUND
    return twice xor (twice ushr LAST_SHIFT)
}

/**
 * One generator's draw for one user.
 *
 * Arithmetic rather than a `Random` seeded per user. A generator is read on
 * the path that books a departure, and an instance per user is an allocation
 * there — a collection pause the run then records in `hiccups` and reads as
 * the target's latency. Nothing here allocates and nothing here is shared.
 */
internal fun draw(user: Long, salt: Long): Long = avalanche(user * GOLDEN + salt)

/** What separates one generator's stream from another's: its seed, and whatever else the caller gave it. */
internal fun salt(seed: Long, parameter: Long): Long = avalanche(seed * GOLDEN + parameter)

/**
 * A draw in `0 until bound`.
 *
 * The shift drops the sign rather than taking an absolute value, which has no
 * answer for `Long.MIN_VALUE`. The modulo leans on the low keys by one part in
 * 2^63 over the bound, which is below anything a load test measures.
 */
internal fun bounded(draw: Long, bound: Long): Long = (draw ushr 1) % bound

/** A `Double` holds 53 bits, so eleven of a `Long`'s go before one of them is rounded away. */
private const val SIGNIFICAND = 53
private const val BEYOND_A_DOUBLE = 11
private const val UNIT = 1.0 / (1L shl SIGNIFICAND)

/** A draw in `[0, 1)`, taken off the top bits rather than the bottom ones. */
internal fun unitInterval(draw: Long): Double = (draw ushr BEYOND_A_DOUBLE) * UNIT
