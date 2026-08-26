package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class InjectionProfileTest {

    @Test
    fun `a constant rate departs on the interval, starting at zero`() {
        val departures = constantRate(4.0.perSecond, over = 1.seconds).departures().toList()

        departures shouldBe listOf(0.milliseconds, 250.milliseconds, 500.milliseconds, 750.milliseconds)
    }

    @Test
    fun `a constant rate sends rate times duration users`() {
        constantRate(50.0.perSecond, over = 60.seconds).userCount() shouldBe 3000L
    }

    @Test
    fun `a rate of zero departs nobody`() {
        constantRate(0.0.perSecond, over = 10.seconds).departures().toList().shouldBeEmpty()
    }

    @Test
    fun `a ramp with equal ends is a constant rate`() {
        val ramp = rampRate(from = 4.0.perSecond, to = 4.0.perSecond, over = 1.seconds).departures().toList()

        ramp shouldBe constantRate(4.0.perSecond, over = 1.seconds).departures().toList()
    }

    @Test
    fun `a ramp sends the area under the line`() {
        rampRate(from = 0.0.perSecond, to = 100.0.perSecond, over = 10.seconds).userCount() shouldBe 500L
    }

    @Test
    fun `a ramp gets faster, so its gaps shrink`() {
        val departures = rampRate(from = 1.0.perSecond, to = 20.0.perSecond, over = 10.seconds).departures().toList()
        val gaps = departures.zipWithNext { earlier, later -> later - earlier }

        withClue("gaps: $gaps") {
            gaps.zipWithNext().all { (wider, narrower) -> narrower <= wider } shouldBe true
        }
    }

    @Test
    fun `every departure lands inside the window it was given`() {
        val over = 10.seconds
        val departures = rampRate(from = 1.0.perSecond, to = 20.0.perSecond, over = over).departures().toList()

        departures.first() shouldBe Duration.ZERO
        withClue("last was ${departures.last()}") { (departures.last() < over) shouldBe true }
    }

    @Test
    fun `a departure is computed from its index, so a long run does not drift`() {
        val hour = constantRate(1000.0.perSecond, over = 3600.seconds)

        hour.departures().elementAt(3_599_999) shouldBe 3599.999.seconds
    }

    @Test
    fun `a negative rate is a bug in the caller, not a failure to report`() {
        shouldThrow<IllegalArgumentException> { constantRate((-1).perSecond, over = 1.seconds) }
        shouldThrow<IllegalArgumentException> { rampRate(from = 0.perSecond, to = (-1).perSecond, over = 1.seconds) }
        shouldThrow<IllegalArgumentException> { constantRate(1.0.perSecond, over = (-1).seconds) }
    }
}
