package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A capture is an arrival process with nothing modelled about it. Replaying it
 * has to send those arrivals and not a shape fitted to them.
 */
class ReplayTest {

    private val noon = Instant.parse("2026-08-26T12:00:00Z")

    private fun at(vararg millis: Long) = arrivalsFrom(
        millis.map { noon.plusNanos(it * 1_000_000) },
        source = "capture.csv",
    )

    @Test
    fun `at one, the departures are the capture's gaps nanosecond for nanosecond`() {
        val replay = at(0, 100, 350, 400).replaying()

        replay.departures().toList() shouldContainExactly
            listOf(0.milliseconds, 100.milliseconds, 350.milliseconds, 400.milliseconds)
    }

    @Test
    fun `at twice the rate every gap and the window halve`() {
        val replay = at(0, 100, 350, 400).replaying(scaled = 2.0)

        replay.departures().toList() shouldContainExactly
            listOf(0.milliseconds, 50.milliseconds, 175.milliseconds, 200.milliseconds)
        replay.over shouldBe 200.milliseconds
    }

    @Test
    fun `scaling leaves the burstiness exactly as it was`() {
        val capture = at(0, 10, 20, 30, 900, 910, 1_000)

        val once = capture.replaying().departures().toList()
        val twice = capture.replaying(scaled = 2.0).departures().toList()

        withClue("time-scaling multiplies every gap by the same number, so the ratio cannot move") {
            once.cov() shouldBe twice.cov().plusOrMinus(0.0001)
        }
    }

    @Test
    fun `a window is cut in the capture's own time, before the scaling`() {
        val capture = at(0, 100, 200, 300, 400, 500)

        val middle = capture.replaying(from = 200.milliseconds, window = 200.milliseconds, scaled = 2.0)

        withClue("200ms in, 200ms long, at twice the rate is 100ms of run") {
            middle.userCount() shouldBe 2L
            middle.departures().toList() shouldContainExactly listOf(0.milliseconds, 50.milliseconds)
        }
    }

    @Test
    fun `how many users it sends is answered before anything departs`() {
        at(0, 100, 200, 300).replaying().userCount() shouldBe 4L
        at(0, 100, 200, 300).replaying(from = 150.milliseconds).userCount() shouldBe 2L
    }

    @Test
    fun `a replay then a hold is one shape that counts both`() {
        val shape = at(0, 100, 200).replaying() then hold(10.perSecond, over = 1.seconds)

        shape.userCount() shouldBe 13L
        shape.over shouldBe 1_200.milliseconds
    }

    @Test
    fun `randomising a replay is refused by name`() {
        val why = shouldThrow<IllegalArgumentException> { at(0, 100, 200).replaying().randomized(seed = 38) }

        why.message.orEmpty() shouldContain "capture.csv"
        why.message.orEmpty() shouldContain "already an arrival process"
    }

    @Test
    fun `a replay is drawn from no seed, because its arrivals are what happened`() {
        at(0, 100, 200).replaying().seeds shouldContainExactly emptyList()
    }

    @Test
    fun `a scale of zero or less is refused where it is written`() {
        shouldThrow<IllegalArgumentException> { at(0, 100).replaying(scaled = 0.0) }
        shouldThrow<IllegalArgumentException> { at(0, 100).replaying(window = 0.seconds) }
    }

    /** The coefficient of variation of the gaps between these departures. */
    private fun List<kotlin.time.Duration>.cov(): Double {
        val gaps = zipWithNext { first, second -> (second - first).inWholeNanoseconds.toDouble() }
        val mean = gaps.average()
        return kotlin.math.sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size) / mean
    }
}
