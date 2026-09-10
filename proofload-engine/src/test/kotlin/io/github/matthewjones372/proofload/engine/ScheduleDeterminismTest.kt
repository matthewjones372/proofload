package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Arm
import io.github.matthewjones372.proofload.InjectionProfile
import io.github.matthewjones372.proofload.constantRate
import io.github.matthewjones372.proofload.departures
import io.github.matthewjones372.proofload.hold
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.rampRate
import io.github.matthewjones372.proofload.randomized
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.thenRampTo
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a booking window hands over, for a stated list of elapsed instants.
 *
 * `BookingTest` beside this asserts the cases by hand. This drives the same
 * window through a whole schedule of fills, including ones that arrive late,
 * and states the properties that have to hold for any of them: nothing booked
 * twice, nothing skipped, nothing out of order, nothing paced.
 *
 * `fill` already takes elapsed time as a parameter, so none of this needs a
 * clock, a virtual clock or an injectable time source. 0124 recommends against
 * adding one and this is why: the seam was already there.
 *
 * Defends invariants 2 and 3.
 */
class ScheduleDeterminismTest {

    @Test
    fun `a fill books everything due within a window of it, and nothing further out`() {
        val booked = drive(
            schedule = constantRate(1.0.perSecond, over = 10.seconds),
            window = 5.seconds,
            fills = listOf(Duration.ZERO),
        )

        booked.single().offsets shouldContainExactly (0..5).map { it.seconds }
    }

    @Test
    fun `a fill that arrives late books its whole backlog in one call and paces nothing`() {
        // The case the spec names: a window that arrives a whole window late
        // does not dribble the backlog out over later fills. Those users left
        // late, which is a lateness the run reports, not a gap to hide.
        val booked = drive(
            schedule = constantRate(1.0.perSecond, over = 10.seconds),
            window = 5.seconds,
            fills = listOf(Duration.ZERO, 10.milliseconds, 3.seconds),
        )

        booked.map { it.offsets.size } shouldContainExactly listOf(6, 0, 3)
        withClue("the third fill takes the whole backlog that fell due while it was away") {
            booked.last().offsets shouldContainExactly listOf(6.seconds, 7.seconds, 8.seconds)
        }
    }

    @Test
    fun `a fill a whole run late books every departure that is left, at once`() {
        val booked = drive(
            schedule = constantRate(1.0.perSecond, over = 10.seconds),
            window = 5.seconds,
            fills = listOf(Duration.ZERO, 60.seconds),
        )

        booked.first().offsets.size shouldBe 6
        booked.last().offsets shouldContainExactly (6..9).map { it.seconds }
    }

    @Test
    fun `two hundred and fifty thousand departures a window late are booked in one call, none lost`() {
        // 50,000 a second, a five-second window, and a fill that arrives a whole
        // window late. The interesting number is that the second call returns
        // all of it rather than the window's worth.
        val schedule = constantRate(50_000.0.perSecond, over = 10.seconds)
        val booked = drive(schedule = schedule, window = 5.seconds, fills = listOf(Duration.ZERO, 5.seconds))

        booked.first().offsets.size shouldBe 250_001
        booked.last().offsets.size shouldBe 249_999
        withClue("every departure the profile named was booked exactly once") {
            booked.sumOf { it.offsets.size }.toLong() shouldBe schedule.departures().count().toLong()
        }
    }

    @Test
    fun `driving a shape to its end books every departure exactly once, in order`() {
        shapes().forEach { (name, profile) ->
            val booked = drive(
                schedule = profile,
                window = 1.seconds,
                // Deliberately uneven, and deliberately including a repeat and a
                // long jump: a pump does not wake on a metronome either.
                fills = listOf(
                    Duration.ZERO, 100.milliseconds, 100.milliseconds, 900.milliseconds,
                    2.seconds, 2.seconds, 30.seconds,
                ),
            )
            val offsets = booked.flatMap { it.offsets }

            withClue("$name booked every departure once") {
                offsets shouldContainExactly profile.departures().toList()
            }
            withClue("$name booked in order") { offsets.shouldBeSorted() }
        }
    }

    @Test
    fun `arms due at the same instant leave in the order the mix names them`() {
        // Both arms send a user at offset zero. Which one goes first has to be a
        // function of the value rather than of which iterator answered, or the
        // same simulation is two different runs.
        val first = arm("first")
        val second = arm("second")

        val order = listOf(first, second).schedule().take(2).map { it.arm.scenario.name }.toList()
        val reversed = listOf(second, first).schedule().take(2).map { it.arm.scenario.name }.toList()

        order shouldContainExactly listOf("first", "second")
        reversed shouldContainExactly listOf("second", "first")
    }

    @Test
    fun `a merged schedule is still in order, and holds every arm's users`() {
        val arms = listOf(
            Arm(scenario("fast") { exec("step") { } }, constantRate(7.0.perSecond, over = 3.seconds)),
            Arm(scenario("slow") { exec("step") { } }, constantRate(2.0.perSecond, over = 3.seconds)),
        )

        val merged = arms.schedule().toList()

        merged.map { it.offset }.shouldBeSorted()
        merged.size.toLong() shouldBe arms.sumOf { it.profile.departures().count().toLong() }
        arms.forEach { arm ->
            withClue("${arm.scenario.name} kept its own user numbers") {
                merged.filter { it.arm === arm }.map { it.user } shouldContainExactly
                    (0 until arm.profile.departures().count().toLong()).toList()
            }
        }
    }

    /** What one `fill` handed over, and what it asked to be woken after. */
    private data class Booked(val offsets: List<Duration>, val again: Duration?)

    /**
     * Drives a [BookingWindow] over [schedule] through [fills], in order,
     * collecting what each call booked.
     *
     * Thirty lines over the `fill` that already exists, and no production type.
     * `BookingWindow` is `internal` and this test is in its module, so nothing
     * is widened to publish a seam for a test.
     */
    private fun drive(
        schedule: InjectionProfile,
        window: Duration,
        fills: List<Duration>,
    ): List<Booked> {
        val booking = BookingWindow(
            schedule.departures().mapIndexed { user, offset -> Departure(any, user.toLong(), offset) }.iterator(),
            window,
        )
        return fills.map { elapsed ->
            val offsets = mutableListOf<Duration>()
            val again = booking.fill(elapsed) { departure -> offsets += departure.offset }
            Booked(offsets, again)
        }
    }

    private fun arm(named: String): Arm =
        Arm(scenario(named) { exec("step") { } }, constantRate(1.0.perSecond, over = 2.seconds))

    private fun shapes(): List<Pair<String, InjectionProfile>> = listOf(
        "a constant rate" to constantRate(4.0.perSecond, over = 3.seconds),
        "a rate under one a second" to constantRate(0.4.perSecond, over = 10.seconds),
        "a shape that sends nobody" to constantRate(1.0.perSecond, over = 500.milliseconds),
        "a ramp" to rampRate(from = 1.0.perSecond, to = 20.0.perSecond, over = 3.seconds),
        "stages" to hold(5.0.perSecond, 2.seconds).thenRampTo(15.0.perSecond, 2.seconds),
        "a drawn rate" to constantRate(30.0.perSecond, over = 3.seconds).randomized(seed = 7),
    )

    private val any = Arm(scenario("any") { exec("step") { } }, constantRate(1.perSecond, over = 1.seconds))
}
