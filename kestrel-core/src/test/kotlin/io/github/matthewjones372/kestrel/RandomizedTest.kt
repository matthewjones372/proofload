package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RandomizedTest {

    @Test
    fun `randomising moves the spacing, not the count`() {
        val even = hold(200.perSecond, over = 10.seconds)

        even.randomized(seed = 20260826).userCount() shouldBe even.userCount()
    }

    @Test
    fun `randomising leaves the window it was given`() {
        val even = hold(200.perSecond, over = 10.minutes)

        even.randomized(seed = 20260826).over shouldBe even.over
    }

    @Test
    fun `every departure lands inside the window that was promised`() {
        val over = 10.seconds
        val departures = hold(200.perSecond, over = over).randomized(seed = 1).departures().toList()

        departures.size shouldBe 2000
        withClue("first was ${departures.first()}, last was ${departures.last()}") {
            (departures.first() >= Duration.ZERO && departures.last() < over) shouldBe true
        }
    }

    @Test
    fun `departures come out in order`() {
        val departures = hold(200.perSecond, over = 10.seconds).randomized(seed = 7).departures().toList()

        withClue("departures must not go backwards") {
            departures.zipWithNext().all { (earlier, later) -> later >= earlier } shouldBe true
        }
    }

    @Test
    fun `one seed produces one sequence`() {
        val shape = hold(200.perSecond, over = 10.seconds).randomized(seed = 20260826)

        shape.departures().toList() shouldBe shape.departures().toList()
    }

    @Test
    fun `two seeds produce different sequences`() {
        val even = hold(200.perSecond, over = 10.seconds)

        val one = even.randomized(seed = 1).departures().toList()
        val other = even.randomized(seed = 2).departures().toList()

        withClue("two seeds produced the same offsets") { (one == other) shouldBe false }
    }

    @Test
    fun `a randomised shape is still a value that compares equal`() {
        val even = hold(200.perSecond, over = 10.seconds)

        even.randomized(seed = 4) shouldBe even.randomized(seed = 4)
    }

    @Test
    fun `randomising twice re-seeds rather than nesting, so one shape has one spelling`() {
        val even = hold(200.perSecond, over = 10.seconds)

        even.randomized(seed = 1).randomized(seed = 2) shouldBe even.randomized(seed = 2)
    }

    @Test
    fun `the gaps vary the way a Poisson process varies, where an even profile does not vary at all`() {
        val even = hold(200.perSecond, over = 30.seconds)

        val metronome = even.departures().gapCoefficientOfVariation()
        withClue("an even profile varied by $metronome") { (metronome < 0.001) shouldBe true }
        val randomised = even.randomized(seed = 20260826).departures().gapCoefficientOfVariation()
        withClue("randomised gaps had a coefficient of variation of $randomised") {
            (randomised > 0.8 && randomised < 1.2) shouldBe true
        }
    }

    @Test
    fun `each stage draws its own numbers, so a hold after a hold does not repeat it`() {
        val stage = hold(200.perSecond, over = 10.seconds)
        val shape = stage.then(stage).randomized(seed = 20260826)

        val departures = shape.departures().toList()
        val first = departures.take(2000)
        val second = departures.drop(2000).map { it - 10.seconds }

        withClue("the second stage repeated the first stage's draws") { (first == second) shouldBe false }
    }

    @Test
    fun `arrivals are drawn a window at a time, so a ten minute run does not sort the whole run first`() {
        val opening = hold(1000.perSecond, over = 10.minutes).randomized(seed = 5).departures().take(1000).toList()

        withClue("the opening window ran to ${opening.last()}, past $ARRIVAL_WINDOW") {
            (opening.last() < ARRIVAL_WINDOW) shouldBe true
        }
    }

    @Test
    fun `a window keeps the users its rate line owed it, so a ramp still ramps`() {
        val ramp = rampRate(from = 0.perSecond, to = 100.perSecond, over = 10.seconds)

        val departures = ramp.randomized(seed = 3).departures().toList()
        val perWindow = departures.groupingBy { it.inWholeSeconds }.eachCount()

        departures.size shouldBe ramp.userCount().toInt()
        withClue("arrivals per second were $perWindow") {
            perWindow.values.toList().zipWithNext().all { (earlier, later) -> later > earlier } shouldBe true
        }
    }
}

private fun Sequence<Duration>.gapCoefficientOfVariation(): Double {
    val gaps = zipWithNext { earlier, later -> (later - earlier).inWholeNanoseconds.toDouble() }.toList()
    val mean = gaps.average()
    val variance = gaps.sumOf { (it - mean) * (it - mean) } / gaps.size
    return sqrt(variance) / mean
}
