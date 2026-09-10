package io.github.matthewjones372.proofload

import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 0122's adversarial table, row by row, with no elapsed time in any of it.
 *
 * Each row states a run as departure instants and the lateness each departure
 * left with, and then asserts everything the result has to say about it
 * together. `HeldScheduleTest`, `OfferedTest` and `MaterialTest` beside this
 * each check one reading against hand-built values; what none of them says is
 * that a single run which fell behind and recovered reports a consistent story
 * across all of them at once, which is what a reader actually meets.
 *
 * `RunRecorder.record` takes both `at` and `schedulingDelay` as parameters, so
 * none of this needs a clock. Its own KDoc says so: "the timeline is then a
 * function of what a caller recorded, and a test of it needs no elapsed time."
 *
 * The validity column of 0122's table is absent on purpose. `Valid`,
 * `Partial(FellBehind)` and `Invalid` are 0121's, and 0121 is unbuilt and
 * waiting on a decision, so there is nothing here to assert against yet.
 *
 * Defends invariants 3, 4 and 5.
 */
class LatenessTableTest {

    // Ten seconds, ten departures a second, so the planned interval is 100 ms
    // and one whole interval of lateness is the line lostGround draws.
    private val startedAt = Instant.parse("2026-08-26T09:00:00Z")
    private val seconds = 10
    private val perSecond = 10
    private val interval = 100.milliseconds
    private val service = 20.milliseconds

    /** A run of [seconds] × [perSecond] departures, each late by whatever [late] says. */
    private fun ran(spacing: Duration = interval, late: (request: Int) -> Duration): RunResult {
        val recorder = RunRecorder(startedAt)
        repeat(seconds * perSecond) { request ->
            recorder.record(
                step = "pay",
                failure = null,
                serviceTime = service,
                schedulingDelay = late(request),
                at = spacing * request,
            )
        }
        return recorder.freeze().copy(
            plan = Plan("checkout", listOf("pay"), hold(perSecond.perSecond, over = seconds.seconds)),
        )
    }

    private fun secondOf(request: Int) = request / perSecond

    @Test
    fun `one departure a millisecond late moves that second and nothing else`() {
        val result = ran { request -> if (request == 35) 1.milliseconds else Duration.ZERO }

        withClue("the whole run's worst lateness is the one millisecond") {
            (result.behind.max >= 1.milliseconds) shouldBe true
            (result.behind.max < interval) shouldBe true
        }
        withClue("the second it happened in moved") { (result.latePerSecond[3].max > Duration.ZERO) shouldBe true }
        withClue("its neighbours did not") {
            result.latePerSecond[2].max shouldBe Duration.ZERO
            result.latePerSecond[4].max shouldBe Duration.ZERO
        }
        withClue("a millisecond against a 100 ms interval is not losing ground") {
            result.lostGround() shouldBe false
            result.heldScheduleFor shouldBe seconds.seconds
        }
    }

    @Test
    fun `the interval is compared against a counted bucket, so exactly one interval loses ground`() {
        // `lostGround` is `behind.p99 > ownInterval`, and `behind.p99` is the
        // top of the bucket the sample landed in rather than the sample. At the
        // full precision of 1/128, 100 ms sits between 2^26 and 2^27 ns where a
        // sub-bucket is 524,288 ns wide: it lands in bucket 190, whose top is
        // 100.139 ms. So a departure exactly one interval late reads as just
        // over one, and the verdict goes the strict way.
        //
        // Stated because it is surprising, and because rounding a measurement
        // *down* to make a boundary read nicely is the interpolation AGENTS.md
        // forbids. The width is the honest thing to carry.
        val exactly = ran { interval }

        withClue("the bucket top is above the interval, so the schedule is gone from the first second") {
            exactly.lostGround() shouldBe true
            exactly.heldScheduleFor shouldBe Duration.ZERO
        }

        val under = ran { interval / 2 }

        withClue("half an interval is under it by far more than a bucket is wide") {
            under.lostGround() shouldBe false
            under.heldScheduleFor shouldBe seconds.seconds
        }
    }

    @Test
    fun `a run late from one second on held its schedule up to that second`() {
        val result = ran { request -> if (secondOf(request) >= 6) 3.seconds else Duration.ZERO }

        result.heldScheduleFor shouldBe 6.seconds
        result.lostGround() shouldBe true
    }

    @Test
    fun `lateness that grows is monotone across the seconds it grew in`() {
        val result = ran { request -> (secondOf(request) * 50).milliseconds }

        val worst = result.latePerSecond.map { it.max }

        withClue("each second is at least as late as the one before: $worst") {
            worst shouldBe worst.sortedBy { it }
        }
    }

    @Test
    fun `a run that fell behind and recovered says when it held, and is flat afterwards`() {
        // Late for seconds 3, 4 and 5, on schedule either side.
        val result = ran { request -> if (secondOf(request) in 3..5) 400.milliseconds else Duration.ZERO }

        withClue("it held until the first second that lost a whole interval") {
            result.heldScheduleFor shouldBe 3.seconds
        }
        withClue("the seconds after the recovery are flat") {
            (6 until seconds).forEach { result.latePerSecond[it].max shouldBe Duration.ZERO }
        }
        withClue("and the seconds before it were too") {
            (0 until 3).forEach { result.latePerSecond[it].max shouldBe Duration.ZERO }
        }
    }

    @Test
    fun `a generator that cannot keep up offers less than it was asked for, losing no arrival`() {
        // Every departure leaves, and every one leaves late, spread over twice
        // the window the profile named. That is the row 0122 cares most about:
        // the load that left is not the load that was asked for, and the
        // difference is visible without a single arrival going missing.
        val result = ran(spacing = interval * 2) { request -> interval * request }

        val offered = requireNotNull(result.offered)

        withClue("no arrival is missing: every one the profile named was recorded") {
            result.timeline.sumOf { it.count } shouldBe (seconds * perSecond).toLong()
        }
        withClue("but they took twice as long to leave, so the share is about a half") {
            offered.share shouldBeLessThan 0.6
            (offered.share > 0.4) shouldBe true
        }
        withClue("and the run ran past the window it was given") {
            (offered.over > result.plan.plannedWindow) shouldBe true
        }
        result.lostGround() shouldBe true
    }

    @Test
    fun `a flat lateness far under the target's tail is not called falling behind`() {
        // MaterialTest states this against hand-built values; here it is a run.
        // Five milliseconds against a 20 ms service time is a quarter, which is
        // well over MATERIAL, so this one *is* behind — the point is that the
        // verdict comes off the target's tail and not off the interval.
        val result = ran { 5.milliseconds }

        withClue("it never lost ground: five milliseconds is nowhere near an interval") {
            result.lostGround() shouldBe false
        }
        withClue("but against a 20 ms tail it is a twentieth or more, so it fell behind") {
            result.fellBehind() shouldBe true
        }
    }
}
