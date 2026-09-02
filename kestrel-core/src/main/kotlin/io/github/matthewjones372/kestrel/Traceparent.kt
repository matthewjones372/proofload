package io.github.matthewjones372.kestrel

import java.util.concurrent.ThreadLocalRandom

/**
 * A W3C `traceparent` for one request, and the trace id inside it.
 *
 * In core rather than beside the client that first sent one: [StepScope.traced]
 * is already here, and every protocol that can carry a trace needs the same
 * ids. A second copy of this in the next module would be a second thing to keep
 * right, and two modules disagreeing about the format is the kind of difference
 * nobody notices until a backend has half a run in it.
 *
 * Nothing here decides *whether* to trace. That is the caller's, per target.
 */
object Traceparent {

    /** One header value: a fresh trace id and parent id, version `00`, flags `00`. */
    fun next(): String = perThread.get().next()

    /** The trace id out of a `traceparent`: the middle field, which is what a backend is searched by. */
    fun idIn(header: String): String = header.substring(TRACE_AT, TRACE_AT + TRACE_DIGITS)
}

/**
 * The load marker on every traced request, so a shared target can tell a run's
 * traffic from its users' before it autoscales for the difference.
 */
const val SYNTHETIC: String = "synthetic=true"

private const val HEX = "0123456789abcdef"

/**
 * Version `00` and flags `00`, with the two ids zeroed until a request fills
 * them. No sampled bit: whether to record is the target's decision, and a load
 * generator that forced it would be setting the sampling policy of a system it
 * does not own.
 */
private const val TEMPLATE = "00-00000000000000000000000000000000-0000000000000000-00"

private const val TRACE_AT = 3

private const val PARENT_AT = 36

/** Half a trace id, and the whole of a parent id. */
private const val HEX_DIGITS = 16

/** A whole trace id: two halves of hex. */
private const val TRACE_DIGITS = 32

private const val BITS_PER_DIGIT = 4

private const val NIBBLE = 0xFL

private const val MIX_A = -0x40a7b892e31b1a47L

private const val MIX_B = -0x6b2fb644ecceee15L

private const val SHIFT_A = 30

private const val SHIFT_B = 27

private const val SHIFT_C = 31

/**
 * One thread's ids, seeded from `ThreadLocalRandom` rather than `SecureRandom`:
 * these name a request in a load test rather than guard anything, and a
 * blocking entropy source on the path being timed is a stall the report would
 * charge to the target.
 */
private val perThread: ThreadLocal<TraceIds> =
    ThreadLocal.withInitial { TraceIds(ThreadLocalRandom.current().nextLong()) }

/**
 * Ids for one thread, confined to it, so nothing here synchronises and no two
 * threads share a counter to contend on.
 */
private class TraceIds(private val origin: Long) {

    // Both of these are mutable because this runs on the path being timed: a
    // fresh buffer per request would be allocation the report reads as the
    // target's latency, so one buffer is rewritten and the header's own string
    // is the only thing a request allocates.
    private val digits = TEMPLATE.toCharArray()
    private var sequence = 0L

    init {
        digits.writeHex(TRACE_AT, origin)
    }

    fun next(): String {
        sequence += 1
        // The trace id is this thread's random half and its counter: unique
        // without a shared counter, and never the all-zero id the format
        // forbids, because the counter starts at one.
        digits.writeHex(TRACE_AT + HEX_DIGITS, sequence)
        // Mixed rather than reused: a parent id equal to half the trace id
        // reads downstream as one span sent twice. The low bit is forced
        // because an all-zero parent id is invalid too.
        digits.writeHex(PARENT_AT, mix(origin xor sequence) or 1L)
        return String(digits)
    }
}

/** Writes [value] as [HEX_DIGITS] lowercase hex digits at [at], high digit first. */
private fun CharArray.writeHex(at: Int, value: Long) {
    for (digit in 0 until HEX_DIGITS) {
        val shift = (HEX_DIGITS - 1 - digit) * BITS_PER_DIGIT
        this[at + digit] = HEX[((value ushr shift) and NIBBLE).toInt()]
    }
}

/** SplitMix64's finalizer: a bijection, so distinct inputs stay distinct. */
private fun mix(value: Long): Long {
    val once = (value xor (value ushr SHIFT_A)) * MIX_A
    val twice = (once xor (once ushr SHIFT_B)) * MIX_B
    return twice xor (twice ushr SHIFT_C)
}
