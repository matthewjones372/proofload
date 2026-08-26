package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GoodputTest {

    private val target = 200.milliseconds

    private fun timingOf(samples: List<Duration>): Timing =
        Histogram().apply { samples.forEach { record(it) } }.timing()

    private fun stepOf(name: String, ok: Long, fast: Int, slow: Int): StepStats {
        val count = (fast + slow).toLong()
        return StepStats(
            name = name,
            count = count,
            ok = ok,
            failures = if (count == ok) emptyMap() else mapOf("status 503" to count - ok),
            serviceTime = timingOf(List(fast) { 20.milliseconds } + List(slow) { 800.milliseconds }),
            responseTime = timingOf(List(fast) { 50.milliseconds } + List(slow) { 900.milliseconds }),
        )
    }

    private fun runOf(vararg steps: StepStats, over: Duration = 10.seconds) = RunResult(
        startedAt = Instant.parse("2026-08-26T09:00:00Z"),
        steps = steps.associateBy { it.name },
        behind = timingOf(listOf(1.milliseconds)),
        plan = Plan(
            scenario = "checkout",
            steps = steps.map { it.name },
            profile = constantRate(10.perSecond, over = over),
        ),
    )

    @Test
    fun `goodput is the successes that came back inside the target, over the window the plan asked for`() {
        val pay = stepOf("pay", ok = 100L, fast = 100, slow = 0)

        pay.goodput(under = target, over = 10.seconds).perSecond shouldBe (10.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a request that missed the target is charged to the successes, so goodput cannot flatter`() {
        val pay = stepOf("pay", ok = 99L, fast = 99, slow = 1)

        val met = pay.met(under = target).shouldBeInstanceOf<Met.Measured>()
        withClue("1% failed and 1% came back slow") {
            met.fraction shouldBe (0.98 plusOrMinus 1e-9)
        }
    }

    @Test
    fun `an abandoned user counts against the step that failed, not against the ones that never ran`() {
        val result = runOf(
            stepOf("browse", ok = 100L, fast = 100, slow = 0),
            stepOf("pay", ok = 90L, fast = 100, slow = 0),
            stepOf("confirm", ok = 90L, fast = 90, slow = 0),
        )

        result["pay"].met(under = target).shouldBeInstanceOf<Met.Measured>().fraction shouldBe (0.9 plusOrMinus 1e-9)
        withClue("the ten users that never reached confirm are pay's failures, and only pay's") {
            result["confirm"].met(under = target)
                .shouldBeInstanceOf<Met.Measured>().fraction shouldBe (1.0 plusOrMinus 1e-9)
        }
    }

    @Test
    fun `a run's goodput counts every step against the one window`() {
        val result = runOf(
            stepOf("browse", ok = 100L, fast = 100, slow = 0),
            stepOf("pay", ok = 90L, fast = 100, slow = 0),
        )

        // 100 good and 90 good, over ten seconds.
        requireNotNull(result.goodput(under = target)).perSecond shouldBe (19.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a result that was never planned has no window to be a rate over`() {
        val fromSamples = runOf(stepOf("pay", ok = 10L, fast = 10, slow = 0)).copy(plan = Plan.none)

        fromSamples.goodput(under = target) shouldBe null
    }

    @Test
    fun `goodput reads response time unless it is told otherwise`() {
        val pay = stepOf("pay", ok = 100L, fast = 0, slow = 100)

        withClue("service time is 800 ms and response time 900 ms, so a 850 ms target separates them") {
            pay.met(under = 850.milliseconds).shouldBeInstanceOf<Met.Measured>().fraction shouldBe
                (0.0 plusOrMinus 1e-9)
            pay.met(under = 850.milliseconds, of = Clock.ServiceTime)
                .shouldBeInstanceOf<Met.Measured>().fraction shouldBe (1.0 plusOrMinus 1e-9)
        }
    }

    @Test
    fun `a step that recorded nothing reports its share absent rather than zero`() {
        val nothing = stepOf("pay", ok = 0L, fast = 0, slow = 0)

        nothing.met(under = target).shouldBeInstanceOf<Met.Absent>()
    }

    @Test
    fun `the planned window is the profile's, and nothing when no profile was named`() {
        runOf(stepOf("pay", ok = 1L, fast = 1, slow = 0), over = 90.seconds).plan.plannedWindow shouldBe 90.seconds
        Plan.none.plannedWindow shouldBe Duration.ZERO
    }
}
