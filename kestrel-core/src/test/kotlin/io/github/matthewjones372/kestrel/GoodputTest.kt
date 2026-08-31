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

    private fun outcomeOf(fast: Int, slow: Int, reasons: Map<Reason, Long>) = Outcome(
        serviceTime = timingOf(List(fast) { 20.milliseconds } + List(slow) { 800.milliseconds }),
        responseTime = timingOf(List(fast) { 50.milliseconds } + List(slow) { 900.milliseconds }),
        reasons = reasons,
    )

    private fun stepOf(
        name: String,
        fast: Int = 0,
        slow: Int = 0,
        failedFast: Int = 0,
        failedSlow: Int = 0,
    ): StepStats {
        val failures = (failedFast + failedSlow).toLong()
        return StepStats(
            name = name,
            ok = outcomeOf(fast, slow, emptyMap()),
            failed = outcomeOf(
                failedFast,
                failedSlow,
                if (failures == 0L) emptyMap() else mapOf(Said("status 503") to failures),
            ),
            serviceTime = outcomeOf(fast + failedFast, slow + failedSlow, emptyMap()).serviceTime,
            responseTime = outcomeOf(fast + failedFast, slow + failedSlow, emptyMap()).responseTime,
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
        val pay = stepOf("pay", fast = 100)

        pay.goodput(under = target, over = 10.seconds).perSecond shouldBe (10.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a slow success and a fast failure are each counted out of goodput`() {
        val pay = stepOf("pay", fast = 98, slow = 1, failedFast = 1)

        val met = pay.met(under = target).shouldBeInstanceOf<Met.Measured>()
        withClue("1% failed and 1% came back slow") {
            met.fraction shouldBe (0.98 plusOrMinus 1e-9)
        }
    }

    @Test
    fun `a failure that was also slow is counted out once rather than twice`() {
        val pay = stepOf("pay", fast = 99, failedSlow = 1)

        val met = pay.met(under = target).shouldBeInstanceOf<Met.Measured>()
        withClue("99 of the 100 requests both succeeded and came back inside the target") {
            met.fraction shouldBe (0.99 plusOrMinus 1e-9)
        }
    }

    @Test
    fun `a step where everything failed met the target with none of them, which is a measurement`() {
        val pay = stepOf("pay", failedFast = 100)

        pay.met(under = target).shouldBeInstanceOf<Met.Measured>().fraction shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `an abandoned user counts against the step that failed, not against the ones that never ran`() {
        val result = runOf(
            stepOf("browse", fast = 100),
            stepOf("pay", fast = 90, failedFast = 10),
            stepOf("confirm", fast = 90),
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
            stepOf("browse", fast = 100),
            stepOf("pay", fast = 90, failedFast = 10),
        )

        // 100 good and 90 good, over ten seconds.
        requireNotNull(result.goodput(under = target)).perSecond shouldBe (19.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a result that was never planned has no window to be a rate over`() {
        val fromSamples = runOf(stepOf("pay", fast = 10)).copy(plan = Plan.none)

        fromSamples.goodput(under = target) shouldBe null
    }

    @Test
    fun `goodput reads response time unless it is told otherwise`() {
        val pay = stepOf("pay", slow = 100)

        withClue("service time is 800 ms and response time 900 ms, so a 850 ms target separates them") {
            pay.met(under = 850.milliseconds).shouldBeInstanceOf<Met.Measured>().fraction shouldBe
                (0.0 plusOrMinus 1e-9)
            pay.met(under = 850.milliseconds, of = Clock.ServiceTime)
                .shouldBeInstanceOf<Met.Measured>().fraction shouldBe (1.0 plusOrMinus 1e-9)
        }
    }

    @Test
    fun `a step that recorded nothing reports its share absent rather than zero`() {
        val nothing = stepOf("pay")

        nothing.met(under = target).shouldBeInstanceOf<Met.Absent>()
    }

    @Test
    fun `the planned window is the profile's, and nothing when no profile was named`() {
        runOf(stepOf("pay", fast = 1), over = 90.seconds).plan.plannedWindow shouldBe 90.seconds
        Plan.none.plannedWindow shouldBe Duration.ZERO
    }
}
