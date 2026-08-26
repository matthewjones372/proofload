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
 */
class Histogram {

    // The one mutable thing here, and the reason is the measurement: a
    // histogram that allocated per sample would be timed as the target's
    // latency. Everything read off it is derived rather than counted twice.
    private val counts = LongArray(SUB_BUCKET_COUNT + BUCKET_COUNT * SUB_BUCKET_HALF + 1)

    val count: Long get() = counts.take(BUCKETS).sum()

    /** Samples that arrived past the ceiling, counted at it rather than dropped. */
    val overflowed: Long get() = counts[OVERFLOW]

    fun record(value: Duration) {
        val nanos = value.inWholeNanoseconds
        require(nanos >= 0) { "a latency cannot be negative, but was $value" }
        if (nanos > CEILING_NANOS) counts[OVERFLOW]++
        counts[indexOf(minOf(nanos, CEILING_NANOS))]++
    }

    fun merge(other: Histogram) {
        for (index in counts.indices) counts[index] += other.counts[index]
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
        val bucket = counts.asSequence().take(BUCKETS)
            .runningFold(0L) { seen, inBucket -> seen + inBucket }
            .indexOfFirst { it >= wanted } - 1

        return if (bucket < 0) CEILING_NANOS.nanoseconds else highestEquivalentOf(bucket).nanoseconds
    }

    private fun indexOf(nanos: Long): Int {
        val bucket = bucketOf(nanos)
        val subBucket = (nanos ushr bucket).toInt()
        return if (bucket == 0) subBucket else (bucket + 1) * SUB_BUCKET_HALF + (subBucket - SUB_BUCKET_HALF)
    }

    private fun highestEquivalentOf(index: Int): Long {
        val bucket = if (index < SUB_BUCKET_COUNT) 0 else index / SUB_BUCKET_HALF - 1
        val subBucket = if (bucket == 0) index else index - (bucket + 1) * SUB_BUCKET_HALF + SUB_BUCKET_HALF
        return ((subBucket.toLong() + 1L) shl bucket) - 1L
    }

    private fun bucketOf(nanos: Long): Int {
        val magnitude = MAX_BIT - (nanos or 1L).countLeadingZeroBits()
        return maxOf(0, magnitude - (SUB_BUCKET_MAGNITUDE - 1))
    }

    companion object {
        /** Worst relative error of any reported percentile. */
        const val PRECISION: Double = 1.0 / SUB_BUCKET_HALF

        val ceiling: Duration get() = CEILING_NANOS.nanoseconds
    }
}

private const val MAX_BIT = 63
private const val MAX_PERCENTILE = 100.0
private const val SUB_BUCKET_MAGNITUDE = 8
private const val SUB_BUCKET_COUNT = 1 shl SUB_BUCKET_MAGNITUDE
private const val SUB_BUCKET_HALF = SUB_BUCKET_COUNT / 2

// One hour. A latency longer than this is a hung connection, not a
// measurement, and the ceiling keeps the table at a few thousand longs.
private const val CEILING_NANOS = 3_600L * 1_000_000_000L
private const val BUCKET_COUNT = 40

// The buckets, and then one slot past them holding the overflow count. Keeping
// it in the same array is what lets `merge` be one loop over one array.
private const val BUCKETS = SUB_BUCKET_COUNT + BUCKET_COUNT * SUB_BUCKET_HALF
private const val OVERFLOW = BUCKETS
