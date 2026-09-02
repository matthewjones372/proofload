package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
}
