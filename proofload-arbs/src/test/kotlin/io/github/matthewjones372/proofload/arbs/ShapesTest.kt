package io.github.matthewjones372.proofload.arbs

import io.github.matthewjones372.proofload.Shape
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.pow

class ShapesTest {

    /**
     * The reason the module exists. The share is worked out from the exponent
     * here rather than pinned as a literal, so the test is the definition of
     * Zipf rather than a record of what this sampler happened to do.
     */
    @Test
    fun `the busiest one per cent of a zipf keyspace gets the share the exponent predicts`() {
        val keys = 1_000_000L
        val skew = 1.1
        val busiest = keys / 100

        val measured = shareUnder(zipf(keys, skew), busiest, keys)
        val predicted = harmonic(busiest, skew) / harmonic(keys, skew)

        withClue("the top 1% took $measured of the traffic, and skew $skew predicts $predicted") {
            abs(measured - predicted) shouldBeLessThan 0.03
        }
    }

    @Test
    fun `a skew of 0 point 8 is a flatter keyspace than 1 point 1, at the same cardinality`() {
        val keys = 100_000L
        val busiest = keys / 100

        val flatter = shareUnder(zipf(keys, 0.8), busiest, keys)
        val steeper = shareUnder(zipf(keys, 1.1), busiest, keys)

        withClue("skew 0.8 gave the top 1% $flatter and skew 1.1 gave it $steeper") {
            flatter shouldBeLessThan steeper
        }
    }

    @Test
    fun `zipf draws a rank inside the keyspace and nothing else`() {
        val arb = zipf(keys = 50L, skew = 1.2)

        val drawn = (0L until 10_000L).map { arb at it }

        drawn.min() shouldBe 0L
        drawn.max() shouldBe 49L
    }

    @Test
    fun `zipf replays`() {
        val arb = zipf(keys = 1_000L, skew = 0.9)

        (0L..999L).map { arb at it } shouldBe (0L..999L).map { zipf(keys = 1_000L, skew = 0.9) at it }
    }

    @Test
    fun `uniform spreads its draws over the keyspace it was given`() {
        val arb = uniform(keys = 10L)

        val counts = (0L until 100_000L).groupingBy { arb at it }.eachCount()

        counts.keys shouldHaveSize 10
        withClue("a tenth of a hundred thousand is ten thousand, and the counts were $counts") {
            counts.values.forEach { abs(it - 10_000).toDouble() shouldBeLessThan 500.0 }
        }
    }

    @Test
    fun `digits are that many digits, zero-padded, so the keyspace is the one the count names`() {
        val arb = digits(count = 4)

        val drawn = (0L until 20_000L).map { arb at it }

        drawn.forEach { it.length shouldBe 4 }
        drawn.all { key -> key.all { it.isDigit() } } shouldBe true
        withClue("four digits is ten thousand keys, and 20,000 draws found ${drawn.toSet().size}") {
            drawn.toSet().size shouldBeGreaterThan 8_000
        }
    }

    @Test
    fun `uuids draws a different one for each user, and each is a version 4 uuid`() {
        val arb = uuids()

        val drawn = (0L until 10_000L).map { arb at it }

        drawn.toSet() shouldHaveSize 10_000
        drawn.forEach { it.version() shouldBe 4 }
        drawn.forEach { it.variant() shouldBe 2 }
    }

    @Test
    fun `weighted sends the traffic where the weights say`() {
        val arb = weighted("hot" to 90.0, "warm" to 9.0, "cold" to 1.0)

        val counts = (0L until 100_000L).groupingBy { arb at it }.eachCount()

        withClue("the split was $counts") {
            abs(counts.getValue("hot") - 90_000).toDouble() shouldBeLessThan 1_000.0
            abs(counts.getValue("warm") - 9_000).toDouble() shouldBeLessThan 500.0
            abs(counts.getValue("cold") - 1_000).toDouble() shouldBeLessThan 200.0
        }
    }

    @Test
    fun `a weight of zero is never drawn`() {
        val arb = weighted("asked for" to 1.0, "never" to 0.0)

        (0L until 10_000L).count { (arb at it) == "never" } shouldBe 0
    }

    @Test
    fun `every generator says what it drew and from what seed`() {
        zipf(keys = 1_000_000L, skew = 1.1).shape shouldBe Shape("zipf(keys=1000000, skew=1.1)", seed = 0L)
        uniform(keys = 500L).shape shouldBe Shape("uniform(keys=500)", seed = 0L)
        digits(count = 9, seed = 3L).shape shouldBe Shape("digits(9)", seed = 3L)
        uuids().shape shouldBe Shape("uuids()", seed = 0L)
        weighted("a" to 1.0, "b" to 3.0).shape shouldBe Shape("weighted(2 options)", seed = 0L)
    }

    @Test
    fun `a shape reads as the sentence a report would print`() {
        zipf(keys = 1_000_000L, skew = 1.1, seed = 7L).shape.toString() shouldBe
            "zipf(keys=1000000, skew=1.1), seed 7"
    }

    /**
     * A `for` over a `LongRange` rather than `count`, which iterates it as an
     * `Iterable<Long>` and boxes a million user numbers on the way past.
     */
    private fun shareUnder(arb: Arb<Long>, rank: Long, users: Long): Double {
        var drawn = 0L
        for (user in 0L until users) {
            if ((arb at user) < rank) drawn++
        }
        return drawn.toDouble() / users
    }

    /** The generalised harmonic number the exponent defines the distribution by. */
    private fun harmonic(terms: Long, skew: Double): Double {
        var total = 0.0
        for (term in 1L..terms) {
            total += term.toDouble().pow(-skew)
        }
        return total
    }
}
