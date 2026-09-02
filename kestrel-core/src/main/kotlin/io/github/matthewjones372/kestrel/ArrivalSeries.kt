package io.github.matthewjones372.kestrel

import java.time.Instant
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * When requests actually arrived, captured from somewhere real.
 *
 * A Poisson generator is closer to production than a metronome, and still not
 * production: real traffic is burstier than an exponential draw, and the
 * burstiness is what fills a queue. A capture is the arrival process itself,
 * with nothing modelled about it.
 *
 * Read once and held as a value, so a run can be counted and its window
 * measured before anything is sent — the same guarantee every other shape
 * gives.
 */
class ArrivalSeries internal constructor(
    /** Offsets from the first arrival, ascending. Empty for a capture read back from a baseline. */
    internal val offsets: LongArray,
    /** Where it came from, for a page to name and a baseline to compare on. */
    val source: String,
    /**
     * What a baseline recorded about a capture it did not keep: count, span
     * and digest. Null for a capture read from arrivals, which computes them.
     */
    private val recalled: Recalled? = null,
) {

    init {
        require(recalled != null || offsets.size >= 2) {
            "a capture needs at least two arrivals to have a gap between them, but $source had ${offsets.size}"
        }
    }

    /** What was recorded about a capture whose arrivals are not here. */
    internal data class Recalled(val count: Int, val span: Duration, val digest: Int)

    /** Whether the arrivals themselves are here, or only what a baseline recorded about them. */
    val isRecalled: Boolean get() = recalled != null

    /** How many arrivals it holds. */
    val count: Int get() = recalled?.count ?: offsets.size

    /** First to last. */
    val span: Duration get() = recalled?.span ?: (offsets.last() - offsets.first()).nanoseconds

    /**
     * How uneven the gaps were: their standard deviation over their mean.
     *
     * Nought for a metronome, one for a Poisson process, and above one for
     * traffic that bunches — which is what a capture is usually for. Comparing
     * this against what a run actually produced is how the page says whether
     * the replay came out.
     */
    val cov: Double
        get() {
            if (recalled != null) return 0.0
            val gaps = DoubleArray(offsets.size - 1) { (offsets[it + 1] - offsets[it]).toDouble() }
            val mean = gaps.average()
            if (mean <= 0.0) return 0.0
            return sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size) / mean
        }

    /**
     * Enough to tell two captures apart without carrying a million timestamps
     * into a baseline or a failure message.
     *
     * Public because `kestrel-baseline` writes it: a file that carried the
     * arrivals themselves would hold the data twice, and one that carried
     * nothing could not refuse a run replayed from a different capture.
     */

    /**
     * A number standing for the arrivals themselves, so a baseline can record
     * one capture and refuse another without keeping the timestamps.
     */
    val digest: Int get() = recalled?.digest ?: offsets.contentHashCode()

    val identity: String get() = "$source/$count/${span.inWholeNanoseconds}/$digest"

    /**
     * The arrivals to send, or a refusal.
     *
     * A capture read back from a baseline knows what it was and not what it
     * held: it is there to be compared against, and running it would send a
     * shape nobody captured.
     */
    internal fun toSend(): LongArray {
        require(recalled == null) {
            "$source was read back from a baseline, which records what a capture was and not its arrivals; " +
                "read the capture itself to replay it"
        }
        return offsets
    }

    override fun equals(other: Any?): Boolean = other is ArrivalSeries && other.identity == identity

    override fun hashCode(): Int = identity.hashCode()

    override fun toString(): String = "ArrivalSeries(source=$source, count=$count, span=$span)"

    companion object {
        /** What a baseline recorded about a capture: enough to compare on, not enough to send. */
        fun recalled(source: String, count: Int, span: Duration, digest: Int): ArrivalSeries =
            ArrivalSeries(LongArray(0), source, Recalled(count, span, digest))
    }
}

/**
 * A capture from [instants], in whatever order they arrived.
 *
 * Sorted rather than refused: an export grouped by something other than time
 * is a normal thing to be handed, and the order of a set of arrivals carries
 * nothing the gaps do not.
 */
fun arrivalsFrom(instants: List<Instant>, source: String): ArrivalSeries {
    require(instants.size >= 2) { "a capture needs at least two arrivals, but $source had ${instants.size}" }
    val sorted = instants.sortedBy { it.epochSecond * NANOS_PER_SECOND + it.nano }
    val first = sorted.first()
    return ArrivalSeries(
        offsets = LongArray(sorted.size) { index -> first.until(sorted[index]) },
        source = source,
    )
}

/**
 * A capture from one column of a CSV, parsed as ISO-8601 instants.
 *
 * Fails here rather than mid-run, as `feeding` does: a column the file does
 * not have, or a row that is not a timestamp, is a mistake to hear about
 * before a window is spent.
 */
fun arrivalsFrom(file: CsvFile, column: String, source: String = "a csv"): ArrivalSeries {
    val values = requireNotNull(file.column(column)) {
        "no column named \"$column\" in $source; it has ${file.columns}"
    }
    val instants = values.mapIndexed { row, value ->
        requireNotNull(value.asInstant()) {
            "row ${row + 1} of \"$column\" in $source is \"$value\", which is not an ISO-8601 instant"
        }
    }
    return arrivalsFrom(instants, source)
}

private fun String.asInstant(): Instant? = runCatching { Instant.parse(trim()) }.getOrNull()

private fun Instant.until(later: Instant): Long =
    (later.epochSecond - epochSecond) * NANOS_PER_SECOND + (later.nano - nano)

private const val NANOS_PER_SECOND = 1_000_000_000L
