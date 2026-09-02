package io.github.matthewjones372.kestrel.export

import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.Timing
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.Deflater

/**
 * One timing as HdrHistogram's V2 compressed payload, base64 of what its own
 * encoder would have produced.
 *
 * Nothing is re-bucketed. This histogram's counter table *is* HdrHistogram's:
 * 256 sub-buckets is two significant digits and 32 is one, a slot's index is
 * its `countsArrayIndex` and a bucket's `upperBound` its
 * `highestEquivalentValue`. So the export is the counts moved across, and a
 * percentile read out of the other tool is the percentile read out of this one.
 *
 * Written here rather than taken from the library, for the reason in
 * [NoThirdPartyDependenciesTest]: `Deflater` and `Base64` are in the JDK, and
 * the library is the oracle on the test classpath instead.
 */
internal fun Timing.asV2Base64(): String =
    Base64.getEncoder().encodeToString(compressed(counters(), significantDigits()))

/**
 * The counter table this timing was frozen from, rebuilt slot by slot.
 *
 * The layout comes from `Histogram` rather than from a second copy of its
 * arithmetic here: a bucket's top names exactly one slot, so the frozen
 * distribution is the table with its empty slots left out.
 */
private fun Timing.counters(): LongArray {
    val table = Histogram.of(requireNotNull(precision) { "a timing that counted nothing has no table to write" })
    val counts = LongArray(table.slots + 1)
    distribution.forEach { bucket -> counts[table.slotOf(bucket.upperBound)] = bucket.count }
    return counts
}

/**
 * How many significant digits this width is, in HdrHistogram's vocabulary.
 *
 * It derives the other way — d digits give the smallest power of two above
 * 2×10^d sub-buckets — so 256 is two and 32 is one, and this is that table
 * read backwards rather than a formula inverted.
 */
private fun Timing.significantDigits(): Int = when (precision) {
    Histogram.PRECISION -> FULL_DIGITS
    else -> COARSE_DIGITS
}

/**
 * The compressed form: an eight-byte header, then the deflated V2 payload.
 *
 * Laid out as `AbstractHistogram.encodeIntoCompressedByteBuffer` lays it out,
 * because the reader on the other side is that class's.
 */
private fun compressed(counts: LongArray, digits: Int): ByteArray {
    val payload = uncompressed(counts, digits)
    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    val deflated = try {
        deflater.setInput(payload)
        deflater.finish()
        val room = ByteArray(payload.size + DEFLATE_HEADROOM)
        val written = deflater.deflate(room)
        room.copyOf(written)
    } finally {
        deflater.end()
    }

    return ByteBuffer.allocate(COMPRESSED_HEADER + deflated.size).run {
        putInt(V2_COMPRESSED_COOKIE)
        putInt(deflated.size)
        put(deflated)
        array()
    }
}

/** The V2 payload: the header HdrHistogram writes, then the counts. */
private fun uncompressed(counts: LongArray, digits: Int): ByteArray {
    val body = ByteBuffer.allocate(counts.size * MAX_ZIGZAG_BYTES)
    fillFromCounts(body, counts)

    return ByteBuffer.allocate(V2_HEADER + body.position()).run {
        putInt(V2_COOKIE)
        putInt(body.position())
        // Nothing here normalises or shifts its table, so the offset is zero
        // and the conversion ratio is one: values are nanoseconds either side.
        putInt(0)
        putInt(digits)
        putLong(LOWEST_DISCERNIBLE)
        putLong(Histogram.ceiling.inWholeNanoseconds)
        putDouble(1.0)
        put(body.array(), 0, body.position())
        array()
    }
}

/**
 * The counts, zig-zag encoded, with a run of empty slots written as one
 * negative number.
 *
 * That run-length is why the format is small: a table of forty powers of two
 * is mostly zeros for any real latency distribution, and a run of them costs
 * one or two bytes rather than one per slot.
 */
private fun fillFromCounts(buffer: ByteBuffer, counts: LongArray) {
    val limit = counts.indexOfLast { it != 0L } + 1
    var slot = 0
    while (slot < limit) {
        val count = counts[slot++]
        var empty = 0L
        if (count == 0L) {
            empty = 1L
            while (slot < limit && counts[slot] == 0L) {
                empty++
                slot++
            }
        }
        if (empty > 1L) zigZag(buffer, -empty) else zigZag(buffer, count)
    }
}

/**
 * A signed long as one to nine bytes, small magnitudes short: the sign is
 * folded into the low bit so that -1 is as cheap as 1, and seven bits travel
 * per byte with the top bit saying whether another follows.
 */
private fun zigZag(buffer: ByteBuffer, value: Long) {
    var zigged = (value shl 1) xor (value shr Long.SIZE_BITS - 1)
    while (zigged ushr SEVEN != 0L) {
        buffer.put(((zigged and LOW_SEVEN) or CONTINUES).toByte())
        zigged = zigged ushr SEVEN
    }
    buffer.put(zigged.toByte())
}

/** Two significant digits: 2×10² needs 256 sub-buckets, which is what a full table has. */
private const val FULL_DIGITS = 2

/** One: 2×10¹ needs 32, which is what the coarse table has. */
private const val COARSE_DIGITS = 1

/** A nanosecond, which is the unit everything here counts in. */
private const val LOWEST_DISCERNIBLE = 1L

// `V2EncodingCookieBase | 0x10`, the low bit of the word-size byte marking the
// run-length encoding above; and the compressed base beside it.
private const val V2_COOKIE = 0x1c849303 or 0x10
private const val V2_COMPRESSED_COOKIE = 0x1c849304 or 0x10

/** Cookie, payload length, normalising offset, digits, lowest, highest, ratio. */
private const val V2_HEADER = 4 + 4 + 4 + 4 + 8 + 8 + 8

/** Cookie and compressed length. */
private const val COMPRESSED_HEADER = 4 + 4

/** A long is nine seven-bit groups at worst. */
private const val MAX_ZIGZAG_BYTES = 9

/** Deflate can grow incompressible input; this is more than it can grow by. */
private const val DEFLATE_HEADROOM = 64

private const val SEVEN = 7
private const val LOW_SEVEN = 0x7FL
private const val CONTINUES = 0x80L
