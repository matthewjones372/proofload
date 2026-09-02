package io.github.matthewjones372.kestrel

import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Where latencies are counted. Log-linear buckets: linear within a power of
 * two, doubling between them, so a microsecond and a second are measured to
 * the same relative precision and the table stays small enough to keep per
 * step.
 *
 * The write side of a measurement. It is a mutable accumulator on purpose —
 * recording a sample must not contend on a lock, or the tool starts measuring
 * itself — and it is read into an immutable `Timing` before it escapes.
 *
 * [precision] is a property of the instance rather than of the type, because a
 * run keeps two sizes of these: one per step at [PRECISION] for the summary,
 * and one per second per step at [COARSE_PRECISION] for the timeline, where a
 * full table a second would be tens of megabytes of counters.
 */
class Histogram private constructor(private val subBucketMagnitude: Int) {

    constructor() : this(FULL_SUB_BUCKET_MAGNITUDE)

    private val subBucketCount = 1 shl subBucketMagnitude
    private val subBucketHalf = subBucketCount / 2
    private val overflow = subBucketCount + BUCKET_COUNT * subBucketHalf

    // The one mutable thing here, and the reason is the measurement: a
    // histogram that allocated per sample would be timed as the target's
    // latency. Everything read off it is derived rather than counted twice.
    //
    // The buckets, and then one slot past them holding the overflow count.
    // Keeping it in the same array is what lets `merge` be one loop over one
    // array.
    private val counts = LongArray(overflow + 1)

    // One trace id per bucket, and only where a run traced: a table of nulls
    // for every untraced run would double what a histogram costs to answer a
    // question nobody asked. Allocated on the first traced sample, so an
    // untraced run never sees it and a traced one pays for it once per step.
    //
    // Last writer wins, which needs no read and no compare: any request that
    // landed in the bucket answers "show me one at this latency", and the
    // newest is the one whose trace is least likely to have been dropped by a
    // backend's retention.
    private var traces: Array<String?>? = null

    /** Worst relative error of any percentile this one reports. */
    val precision: Double get() = 1.0 / subBucketHalf

    /** What the counter table costs, which is what buys a histogram a second. */
    internal val counterBytes: Long get() = counts.size.toLong() * Long.SIZE_BYTES

    val count: Long get() = counts.take(overflow).sum()

    /** Samples that arrived past the ceiling, counted at it rather than dropped. */
    val overflowed: Long get() = counts[overflow]

    fun record(value: Duration) = record(value, trace = null)

    /**
     * The same, remembering [trace] as this bucket's exemplar where one is
     * given.
     *
     * One id per bucket rather than per request: per request is a memory
     * profile, and per bucket lands a reader on a real request at the latency
     * they asked about, which is the whole question.
     */
    fun record(value: Duration, trace: String?) {
        val nanos = value.inWholeNanoseconds
        require(nanos >= 0) { "a latency cannot be negative, but was $value" }
        if (nanos > CEILING_NANOS) counts[overflow]++
        val index = indexOf(minOf(nanos, CEILING_NANOS))
        counts[index]++
        if (trace != null) tracing()[index] = trace
    }

    private fun tracing(): Array<String?> =
        traces ?: arrayOfNulls<String>(overflow + 1).also { traces = it }

    /**
     * Refused across precisions: the two tables index differently, so adding
     * one to the other slot by slot would report a latency nothing measured.
     */
    fun merge(other: Histogram) {
        require(other.subBucketMagnitude == subBucketMagnitude) {
            "a histogram good to $precision cannot take one good to ${other.precision}"
        }
        for (index in counts.indices) counts[index] += other.counts[index]
        // Theirs win where they have one: a merge is shards of one run, so
        // either id names a request that really landed in that bucket.
        other.traces?.let { theirs ->
            val mine = tracing()
            for (index in theirs.indices) theirs[index]?.let { mine[index] = it }
        }
    }

    val max: Duration get() = percentile(MAX_PERCENTILE)

    /**
     * The top of the bucket the percentile fell in, never a point interpolated
     * between two buckets. An interpolated percentile is a number nobody
     * measured, and rounding up is the direction that cannot flatter a target.
     */
    fun percentile(percentile: Double): Duration {
        require(percentile in 0.0..MAX_PERCENTILE) { "percentile must be in 0..100, but was $percentile" }
        if (count == 0L) return Duration.ZERO

        val wanted = maxOf(1L, ceil(percentile / MAX_PERCENTILE * count).toLong())
        // runningFold is lazy, so this walks only as far as the bucket the
        // percentile falls in; the leading zero it emits is why the index
        // steps back by one.
        val bucket = counts.asSequence().take(overflow)
            .runningFold(0L) { seen, inBucket -> seen + inBucket }
            .indexOfFirst { it >= wanted } - 1

        return if (bucket < 0) CEILING_NANOS.nanoseconds else highestEquivalentOf(bucket).nanoseconds
    }

    /**
     * The buckets that counted something, in order.
     *
     * Only the non-empty ones: the table has a few thousand slots and a run
     * fills tens of them, and a report that carried the rest would be mostly
     * zeroes on the wire.
     */
    fun distribution(): List<Bucket> = counts.asSequence().take(overflow)
        .mapIndexedNotNull { index, seen ->
            if (seen == 0L) null else Bucket(highestEquivalentOf(index).nanoseconds, seen, traces?.get(index))
        }
        .toList()

    private fun indexOf(nanos: Long): Int {
        val bucket = bucketOf(nanos)
        val subBucket = (nanos ushr bucket).toInt()
        return if (bucket == 0) subBucket else (bucket + 1) * subBucketHalf + (subBucket - subBucketHalf)
    }

    private fun highestEquivalentOf(index: Int): Long {
        val bucket = if (index < subBucketCount) 0 else index / subBucketHalf - 1
        val subBucket = if (bucket == 0) index else index - (bucket + 1) * subBucketHalf + subBucketHalf
        return ((subBucket.toLong() + 1L) shl bucket) - 1L
    }

    private fun bucketOf(nanos: Long): Int {
        val magnitude = MAX_BIT - (nanos or 1L).countLeadingZeroBits()
        return maxOf(0, magnitude - (subBucketMagnitude - 1))
    }

    companion object {
        /** Worst relative error of any percentile a full histogram reports. */
        const val PRECISION: Double = 1.0 / FULL_SUB_BUCKET_HALF

        /** The same for [coarse], which is what the page has to print beside a timeline. */
        const val COARSE_PRECISION: Double = 1.0 / COARSE_SUB_BUCKET_HALF

        /** Thirty-two sub-buckets rather than two hundred and fifty-six: an eighth of the counters. */
        fun coarse(): Histogram = Histogram(COARSE_SUB_BUCKET_MAGNITUDE)

        val ceiling: Duration get() = CEILING_NANOS.nanoseconds
    }
}

private const val MAX_BIT = 63
private const val MAX_PERCENTILE = 100.0
private const val FULL_SUB_BUCKET_MAGNITUDE = 8
private const val COARSE_SUB_BUCKET_MAGNITUDE = 5
private const val FULL_SUB_BUCKET_HALF = 1 shl (FULL_SUB_BUCKET_MAGNITUDE - 1)
private const val COARSE_SUB_BUCKET_HALF = 1 shl (COARSE_SUB_BUCKET_MAGNITUDE - 1)

// One hour. A latency longer than this is a hung connection, not a
// measurement, and the ceiling keeps the table at a few thousand longs.
private const val CEILING_NANOS = 3_600L * 1_000_000_000L

// Enough doublings to reach the ceiling from the coarsest sub-bucket count
// above, which is the one that needs the most of them.
private const val BUCKET_COUNT = 40
