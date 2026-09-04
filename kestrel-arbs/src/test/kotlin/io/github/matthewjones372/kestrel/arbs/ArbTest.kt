package io.github.matthewjones372.kestrel.arbs

import com.sun.management.ThreadMXBean
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

class ArbTest {

    private val basket = oneOf("anvil", "rocket", "birdseed")

    @Test
    fun `a generator answers the same value for the same user every time it is asked`() {
        val drawn = (0L..99L).map { basket at it }

        drawn shouldBe (0L..99L).map { basket at it }
    }

    /**
     * The literals are the claim: `at` is arithmetic over the user's number
     * with nothing read from the environment, so what is pinned here is what
     * another JVM computes.
     */
    @Test
    fun `the first ten users draw what they drew when this was written`() {
        val drawn = (0L..9L).map { basket at it }

        drawn shouldContainExactly listOf(
            "anvil",
            "rocket",
            "birdseed",
            "birdseed",
            "anvil",
            "anvil",
            "birdseed",
            "anvil",
            "rocket",
            "anvil",
        )
    }

    @Test
    fun `two generators built the same way draw alike`() {
        val again = oneOf("anvil", "rocket", "birdseed")

        (0L..999L).map { again at it } shouldBe (0L..999L).map { basket at it }
    }

    @Test
    fun `a seed separates two generators that would otherwise agree`() {
        val other = oneOf(listOf("anvil", "rocket", "birdseed"), seed = 42L)

        val agreements = (0L..999L).count { (other at it) == (basket at it) }

        withClue("two seeds of one shape agreed $agreements times in a thousand, so the seed did nothing") {
            agreements.toLong() shouldBeLessThan 500L
        }
    }

    @Test
    fun `every value is drawn by somebody`() {
        val drawn = (0L..999L).map { basket at it }.toSet()

        drawn shouldBe setOf("anvil", "rocket", "birdseed")
    }

    @Test
    fun `map turns the draw into the caller's own keyspace without moving it`() {
        val upper = basket.map { it.uppercase() }

        (0L..99L).map { upper at it } shouldBe (0L..99L).map { (basket at it).uppercase() }
    }

    @Test
    fun `map keeps the shape it drew from, because renaming a key does not change its cardinality`() {
        basket.map { it.length }.shape shouldBe basket.shape
    }

    @Test
    fun `a generator says what it draws`() {
        basket.shape shouldBe Shape("oneOf(3 values)", seed = 0L)
    }

    /**
     * A generator is read on the path that books a departure, so an allocation
     * per draw is a collection pause the run records in `hiccups` and reads as
     * the target's latency. A million draws inside a megabyte is under a byte
     * each; one boxed `Long` per draw would be sixteen megabytes.
     */
    @Test
    fun `a million draws allocate nothing per draw`() {
        val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val id = Thread.currentThread().threadId()

        // Twice: the second is measured with the classes loaded and the loop
        // compiled, so warm-up allocation is the JVM's rather than the draw's.
        drawEveryUser()
        val before = threads.getThreadAllocatedBytes(id)
        drawEveryUser()
        val allocated = threads.getThreadAllocatedBytes(id) - before

        withClue("a million draws allocated $allocated bytes") {
            allocated shouldBeLessThan DRAWS
        }
    }

    /**
     * A `for` over a `LongRange` rather than `sumOf`, which iterates it as an
     * `Iterable<Long>` and boxes every user number — the measurement above
     * would then be counting the loop.
     */
    private fun drawEveryUser(): Int {
        var length = 0
        for (user in 0L until DRAWS) {
            length += (basket at user).length
        }
        return length
    }

    private companion object {
        const val DRAWS = 1_000_000L
    }
}
