package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Two hundred users that met a constant pause together leave it together and
 * click again in the same instant. A drawn wait is what stops the metronome
 * 0034 took out of the arrivals reappearing inside the journey.
 */
class ThinkTimeTest {

    private val checkout = scenario("checkout") {
        exec("browse") { }
        pause(2.seconds)
        exec("pay") { }
    }

    @Test
    fun `a duration still builds a constant pause, and every scenario written before this is unchanged`() {
        checkout.thinkTimes shouldContainExactly listOf(ThinkTime.Constant(2.seconds))
        checkout.drawsThinkTime shouldBe false
    }

    @Test
    fun `a constant waits the same however it is drawn`() {
        val constant = constant(2.seconds)

        constant.drawnFrom(Random(1)) shouldBe 2.seconds
        constant.drawnFrom(Random(2)) shouldBe 2.seconds
    }

    @Test
    fun `one distribution drawn twice matches on one seed and differs on two`() {
        val think = exponential(mean = 2.seconds)

        think.drawnFrom(Random(38)) shouldBe think.drawnFrom(Random(38))
        think.drawnFrom(Random(38)) shouldNotBe think.drawnFrom(Random(39))
    }

    @Test
    fun `an exponential draws about its mean over many users`() {
        val think = exponential(mean = 2.seconds)
        val random = Random(38)

        val drawn = List(20_000) { think.drawnFrom(random).inWholeMilliseconds }.average()

        withClue("$drawn ms against 2000") { drawn shouldBe 2_000.0.plusOrMinus(120.0) }
    }

    @Test
    fun `a lognormal sits around its median with a tail above it`() {
        val think = lognormal(median = 1.seconds, sigma = 0.5)
        val random = Random(38)

        val drawn = List(20_000) { think.drawnFrom(random).inWholeMilliseconds }.sorted()

        withClue("the middle of the draws is the median it was given") {
            drawn[drawn.size / 2].toDouble() shouldBe 1_000.0.plusOrMinus(40.0)
        }
        withClue("and the mean sits above it, which is what a right tail does") {
            (drawn.average() > drawn[drawn.size / 2]) shouldBe true
        }
    }

    @Test
    fun `a uniform stays inside its range`() {
        val think = uniform(from = 1.seconds, until = 3.seconds)
        val random = Random(38)

        repeat(1_000) {
            val drawn = think.drawnFrom(random)
            withClue("$drawn") { (drawn >= 1.seconds && drawn < 3.seconds) shouldBe true }
        }
    }

    @Test
    fun `a wait that cannot be drawn from is refused where it is written`() {
        shouldThrow<IllegalArgumentException> { exponential(mean = Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { lognormal(median = 1.seconds, sigma = 0.0) }
        shouldThrow<IllegalArgumentException> { uniform(from = 2.seconds, until = 1.seconds) }
        shouldThrow<IllegalArgumentException> { constant((-1).milliseconds) }
    }

    @Test
    fun `a scenario that draws says so, wherever the pause is in the tree`() {
        val looping = scenario("looping") {
            repeat(3) {
                exec("poll") { }
                pause(exponential(mean = 500.milliseconds))
            }
        }

        looping.drawsThinkTime shouldBe true
        looping.thinkTimes shouldContainExactly listOf(ThinkTime.Exponential(500.milliseconds))
    }
}
