package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SearchTest {

    private val pay = step("pay")
    private val checkout = scenario("checkout") { exec(pay) { } }
    private val fast = p99(pay) under 200.milliseconds

    private val search = checkout.sustainable(
        upTo = 10_000.perSecond,
        holding = 2.minutes,
        expecting = listOf(fast),
    )

    @Test
    fun `a search says which rates it will try before anything is sent`() {
        search.rungs shouldBe (1..10).map { tenth -> (tenth * 1_000).perSecond }
    }

    @Test
    fun `a search says the longest it can take before anybody starts it`() {
        search.worstCase shouldBe 30.minutes
    }

    @Test
    fun `a rung is the scenario held at one rate, judged by the goals the search was given`() {
        search.fedBy(Feeder.empty).at(2_000.perSecond) shouldBe
            Simulation(checkout, constantRate(2_000.perSecond, over = 2.minutes), Feeder.empty, listOf(fast))
    }

    @Test
    fun `a judge that fails above a rate is found to within one ladder step of it`() {
        val capacity = search.judgedBy(failingAbove(4_800.perSecond))

        val found = capacity.rate.shouldNotBeNull().perSecond
        withClue("the highest passing rate must not claim more than the judge allowed") {
            found shouldBeLessThanOrEqual 4_800.0
        }
        withClue("a step of the ladder is 1,000/s, and bisection must land inside one") {
            found shouldBeGreaterThan 3_800.0
        }
    }

    @Test
    fun `the goal that stopped the search is the one the first failing rung missed`() {
        search.judgedBy(failingAbove(4_800.perSecond)).limitedBy shouldBe fast
    }

    @Test
    fun `the ladder carries on past the knee, so the shape past it can be read`() {
        val curve = search.judgedBy(failingAbove(4_800.perSecond)).curve.map { it.rate }

        curve shouldContain 7_000.perSecond
        withClue("two rungs past the first failing one, and then it stops") {
            curve shouldNotContain 8_000.perSecond
        }
    }

    @Test
    fun `a rung the injector could not offer is void, and does not end up as the answer`() {
        val capacity = search.judgedBy(fallingBehindAbove(3_000.perSecond))

        capacity.curve.last().outcome shouldBe Rung.Outcome.Void
        withClue("nothing was learned about the target, so no goal limited it") {
            capacity.limitedBy shouldBe null
        }
        withClue("4,000/s was never offered, so it cannot be reported as sustained") {
            capacity.voided shouldBe true
            capacity.rate shouldBe 3_000.perSecond
        }
    }

    @Test
    fun `a search whose lowest rung already misses reports no sustainable rate`() {
        val capacity = search.judgedBy(failingAbove(0.perSecond))

        capacity.rate shouldBe null
        capacity.limitedBy shouldBe fast
    }

    @Test
    fun `the curve holds every rung that ran, and each carries the verdicts it was judged on`() {
        val capacity = search.judgedBy(failingAbove(4_800.perSecond))

        withClue("the rates climb, so the curve reads left to right") {
            capacity.curve.map { it.rate.perSecond } shouldBe capacity.curve.map { it.rate.perSecond }.sorted()
        }
        capacity.curve.first().verdicts.single().met shouldBe true
        capacity.curve.first().outcome shouldBe Rung.Outcome.Passed
    }

    @Test
    fun `a search will not accept a ceiling of nothing to search below`() {
        shouldThrow<IllegalArgumentException> {
            checkout.sustainable(upTo = 0.perSecond, holding = 2.minutes, expecting = listOf(fast))
        }
        shouldThrow<IllegalArgumentException> {
            checkout.sustainable(upTo = 10.perSecond, holding = Duration.ZERO, expecting = listOf(fast))
        }
    }

    /** A target that answers inside the goal up to [ceiling] and falls off a cliff above it. */
    private fun failingAbove(ceiling: Rate): (Simulation) -> RunResult = { simulation ->
        val took = if (simulation.rate() <= ceiling.perSecond) 100.milliseconds else 900.milliseconds
        simulation.resultOf(took = took, behind = Duration.ZERO)
    }

    /** A generator that cannot offer more than [ceiling], against a target that never misses. */
    private fun fallingBehindAbove(ceiling: Rate): (Simulation) -> RunResult = { simulation ->
        val behind = if (simulation.rate() <= ceiling.perSecond) Duration.ZERO else 30.seconds
        simulation.resultOf(took = 100.milliseconds, behind = behind)
    }

    private fun Simulation.rate(): Double = (profile as InjectionProfile.ConstantRate).perSecond

    private fun Simulation.resultOf(took: Duration, behind: Duration): RunResult =
        RunResult(
            startedAt = Instant.EPOCH,
            steps = mapOf(pay.name to stepOf(took)),
            behind = timingOf(behind),
            plan = plan(),
        )

    private fun stepOf(took: Duration): StepStats {
        val timing = timingOf(took)
        return StepStats(pay.name, Outcome(timing, timing), Outcome.none, timing, timing)
    }

    private fun timingOf(value: Duration): Timing = Histogram().apply { record(value) }.timing()
}
