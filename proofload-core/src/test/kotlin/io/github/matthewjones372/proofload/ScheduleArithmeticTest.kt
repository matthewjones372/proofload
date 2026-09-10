package io.github.matthewjones372.proofload

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a profile names, proven as arithmetic rather than measured.
 *
 * `ScheduleDriftTest` sends 2,000 a second for two seconds and reads a
 * distribution, which is the right test for the claim it makes and the wrong
 * one for these: how many arrivals a shape names and where they fall is a
 * function of values, and asserting it through a scheduler proves it at the
 * resolution of a shared build machine. Nothing here reads a clock.
 *
 * Defends invariants 1, 3 and 14.
 */
class ScheduleArithmeticTest {

    // The counting cases from 0124. Each is a shape whose user count is not the
    // one a reader would get by multiplying in their head, and none of them was
    // asserted anywhere before.

    @Test
    fun `a window too short for one arrival departs nobody`() {
        // The run that sends nothing and today still meets a failure-rate goal,
        // which is 0121's to fix. Here only to state that it really is zero.
        val profile = constantRate(1.0.perSecond, over = 500.milliseconds)

        profile.userCount() shouldBe 0L
        profile.departures().toList().shouldBeEmpty()
    }

    @Test
    fun `a rate below one a second is not rounded up`() {
        val profile = constantRate(0.4.perSecond, over = 10.seconds)

        profile.userCount() shouldBe 4L
        profile.departures().toList() shouldContainExactly
            listOf(0.seconds, 2.5.seconds, 5.seconds, 7.5.seconds)
    }

    @Test
    fun `a half arrival at the end of the window is dropped, not rounded`() {
        // 3 a second for ten and a half seconds is 31.5 users. Not 32, and not
        // 31.5 truncated somewhere later where it would be a fraction of a user
        // arriving.
        constantRate(3.0.perSecond, over = 10.5.seconds).userCount() shouldBe 31L
    }

    @Test
    fun `the window is half-open, so nobody departs exactly at over`() {
        // Stated as a test rather than as a comment, because it is the reason
        // `hold(r, 1s) then hold(r, 1s)` sends what `hold(r, 2s)` sends. A
        // closed boundary would send one more.
        val departures = constantRate(4.0.perSecond, over = 1.seconds).departures().toList()

        departures.last() shouldBe 750.milliseconds
        departures.none { it >= 1.seconds } shouldBe true
    }

    @Test
    fun `splitting a hold in two sends what holding once sends`() {
        val once = constantRate(3.0.perSecond, over = 2.seconds)
        val twice = hold(3.0.perSecond, 1.seconds) then hold(3.0.perSecond, 1.seconds)

        twice.userCount() shouldBe once.userCount()
        twice.departures().toList() shouldContainExactly once.departures().toList()
    }

    @Test
    fun `a ramp whose ends are equal does not divide by its acceleration`() {
        // The quadratic solves for t with an acceleration of zero underneath it.
        // A shape nobody would write on purpose and every ramp passes through.
        val ramp = rampRate(from = 5.0.perSecond, to = 5.0.perSecond, over = 2.seconds)

        ramp.userCount() shouldBe 10L
        ramp.departures().toList() shouldContainExactly
            constantRate(5.0.perSecond, over = 2.seconds).departures().toList()
    }

    // Invariant 1: an arrival schedule is a function of the profile and the
    // arrival's index, and of nothing else.

    @Test
    fun `every shape names exactly as many departures as it counts`() {
        shapes().forEach { (name, profile) ->
            withClue(name) { profile.departures().count().toLong() shouldBe profile.userCount() }
        }
    }

    @Test
    fun `every departure falls inside the window, and none is negative`() {
        shapes().forEach { (name, profile) ->
            withClue(name) {
                profile.departures().forEach { offset ->
                    (offset >= Duration.ZERO) shouldBe true
                    (offset < profile.over) shouldBe true
                }
            }
        }
    }

    /** Invariant 3: booking is never paced, which starts with offsets that never run backwards. */
    @Test
    fun `every shape hands out departures in non-decreasing order`() {
        shapes().forEach { (name, profile) ->
            val offsets = profile.departures().toList()
            withClue(name) { offsets shouldContainExactly offsets.sorted() }
        }
    }

    @Test
    fun `asking a shape twice gives the same answer`() {
        shapes().forEach { (name, profile) ->
            withClue(name) { profile.departures().toList() shouldContainExactly profile.departures().toList() }
        }
    }

    // Invariant 7 of 0124's list: a draw is a pure function of its seed.

    @Test
    fun `the same seed draws the same arrivals`() {
        val shape = constantRate(50.0.perSecond, over = 4.seconds)

        shape.randomized(seed = 17).departures().toList() shouldContainExactly
            shape.randomized(seed = 17).departures().toList()
    }

    @Test
    fun `a different seed draws different arrivals, and the same number of them`() {
        val shape = constantRate(50.0.perSecond, over = 4.seconds)

        val one = shape.randomized(seed = 17).departures().toList()
        val other = shape.randomized(seed = 18).departures().toList()

        one.size shouldBe other.size
        (one == other) shouldBe false
    }

    @Test
    fun `a drawn shape keeps the count and the window of the shape underneath it`() {
        val shape = rampRate(from = 10.0.perSecond, to = 40.0.perSecond, over = 3.seconds)
        val drawn = shape.randomized(seed = 99)

        drawn.userCount() shouldBe shape.userCount()
        drawn.departures().count().toLong() shouldBe shape.userCount()
        drawn.departures().forEach { (it < shape.over) shouldBe true }
    }

    @Test
    fun `a drawn shape still departs in order`() {
        val offsets = constantRate(200.0.perSecond, over = 5.seconds).randomized(seed = 4).departures().toList()

        offsets shouldContainExactly offsets.sorted()
    }

    /**
     * Every shape that states departures, including the ones a reader would not
     * think to write. `ClosedUsers` is absent on purpose: it states none, and
     * `ClosedModelTest` is where that refusal is asserted.
     */
    private fun shapes(): List<Pair<String, InjectionProfile>> = listOf(
        "a constant rate" to constantRate(7.0.perSecond, over = 3.seconds),
        "a rate under one a second" to constantRate(0.4.perSecond, over = 10.seconds),
        "a rate of zero" to constantRate(0.0.perSecond, over = 5.seconds),
        "a window too short to send anybody" to constantRate(1.0.perSecond, over = 500.milliseconds),
        "a ramp up" to rampRate(from = 1.0.perSecond, to = 20.0.perSecond, over = 4.seconds),
        "a ramp down" to rampRate(from = 20.0.perSecond, to = 1.0.perSecond, over = 4.seconds),
        "a ramp that does not move" to rampRate(from = 5.0.perSecond, to = 5.0.perSecond, over = 2.seconds),
        "a ramp from nothing" to rampRate(from = 0.0.perSecond, to = 10.0.perSecond, over = 3.seconds),
        "stages" to hold(5.0.perSecond, 2.seconds).thenRampTo(15.0.perSecond, 2.seconds),
        "a drawn constant rate" to constantRate(30.0.perSecond, over = 3.seconds).randomized(seed = 1),
        "a drawn ramp" to rampRate(from = 2.0.perSecond, to = 30.0.perSecond, over = 3.seconds).randomized(seed = 2),
    )
}
