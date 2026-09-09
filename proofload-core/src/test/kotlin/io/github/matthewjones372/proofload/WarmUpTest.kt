package io.github.matthewjones372.proofload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A warm-up is declared on the run and carried by the plan, so the page can say
 * what was thrown away and a comparison can refuse to pool a warmed run with a
 * cold one.
 */
class WarmUpTest {

    private val checkout = scenario("checkout") { exec("browse") { } }

    @Test
    fun `a run says what it warms and for how long, before it sends anything`() {
        val warmed = checkout.at(45.perSecond, over = 2.minutes()).warmingUp(5.seconds)

        warmed.plan().warmUp shouldBe WarmUp(5.seconds)
    }

    @Test
    fun `a run that warms nothing says so rather than warming for no time`() {
        checkout.at(45.perSecond, over = 2.minutes()).plan().warmUp shouldBe null
    }

    @Test
    fun `a warm-up cannot run backwards, or for no time at all`() {
        shouldThrow<IllegalArgumentException> { WarmUp((-1).seconds) }
        shouldThrow<IllegalArgumentException> { WarmUp(kotlin.time.Duration.ZERO) }
            .message shouldContain "warm-up"
    }

    @Test
    fun `a warmed run and a cold one are not the same population`() {
        val cold = checkout.at(45.perSecond, over = 2.minutes()).plan()
        val warmed = checkout.at(45.perSecond, over = 2.minutes()).warmingUp(5.seconds).plan()

        warmed.unlike(cold).size shouldBe 1
        warmed.unlike(cold).single() shouldContain "warm-up"
        warmed.unlike(warmed).shouldBeEmpty()
    }

    @Test
    fun `the rate a shape opens at is what a warm-up holds`() {
        hold(45.perSecond, over = 1.minutes()).startRate.perSecond shouldBe 45.0
        rampRate(from = 10.perSecond, to = 90.perSecond, over = 1.minutes()).startRate.perSecond shouldBe 10.0
        hold(10.perSecond, over = 1.minutes()).thenRampTo(90.perSecond, over = 1.minutes())
            .startRate.perSecond shouldBe 10.0
        hold(45.perSecond, over = 1.minutes()).randomized(seed = 1L).startRate.perSecond shouldBe 45.0
    }

    private fun Int.minutes() = (this * 60).seconds

    @Test
    fun `the line a run announces itself with names the warm-up`() {
        val warmed = checkout.at(45.perSecond, over = 2.minutes()).warmingUp(5.seconds).plan()

        val printed = printed { Progress.lines().starting(warmed) }

        printed shouldContain "after warming for 5s"
    }

    @Test
    fun `a run that warms nothing announces nothing about warming`() {
        val printed = printed { Progress.lines().starting(checkout.at(45.perSecond, over = 2.minutes()).plan()) }

        printed shouldNotContain "warming"
    }

    private fun printed(block: () -> Unit): String {
        val out = java.io.ByteArrayOutputStream()
        val before = System.out
        System.setOut(java.io.PrintStream(out))
        try {
            block()
        } finally {
            System.setOut(before)
        }
        return out.toString()
    }

    @Test
    fun `a search warms every rung at that rung's own rate`() {
        val search = checkout
            .sustainable(upTo = 100.perSecond, holding = 10.seconds, expecting = emptyList())
            .warmingUp(2.seconds)

        val rung = search.at(40.perSecond)

        rung.warmUp shouldBe WarmUp(2.seconds)
        rung.plan().warmUp shouldBe WarmUp(2.seconds)
        // The rung's own shape opens at the rung's rate, so that is what warms.
        rung.profile.startRate.perSecond shouldBe 40.0
    }

    @Test
    fun `the bound a search quotes counts a warm-up for every rung and every bisection`() {
        val cold = checkout.sustainable(upTo = 100.perSecond, holding = 10.seconds, expecting = emptyList())
        val warmed = cold.warmingUp(2.seconds)

        val rungsAndBisections = cold.worstCase / 10.seconds

        warmed.worstCase shouldBe cold.worstCase + 2.seconds * rungsAndBisections
        warmed.atMostAfter(climbed = 3) shouldBe
            cold.atMostAfter(climbed = 3) + 2.seconds * (cold.atMostAfter(climbed = 3) / 10.seconds)
    }
}
