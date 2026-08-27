package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val pay = step("pay")

class DifferenceTest {

    private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

    private val paying = Plan(
        scenario = "paying",
        steps = listOf("pay"),
        profile = constantRate(100.perSecond, over = 2.seconds),
    )

    /** Five runs that landed a little apart, which is what an interval across runs is made of. */
    private val spreadOut = listOf(98, 99, 100, 101, 102)

    private fun runOf(latency: Duration, plan: Plan = paying): RunResult {
        val timing = Histogram().apply { repeat(SAMPLES) { record(latency) } }.timing()
        return RunResult(
            startedAt = Instant.parse("2026-08-26T09:00:00Z"),
            steps = mapOf(
                "pay" to StepStats(
                    name = "pay",
                    ok = Outcome(timing, timing),
                    failed = Outcome.none,
                    serviceTime = timing,
                    responseTime = timing,
                ),
            ),
            behind = Histogram().apply { repeat(SAMPLES) { record(Duration.ZERO) } }.timing(),
            plan = plan,
            machine = here,
        )
    }

    /** A hundred injector stalls: [typically] for ninety-nine of them, and [worst] for the hundredth. */
    private fun stallsOf(worst: Duration, typically: Duration = worst): Timing =
        Histogram().apply {
            repeat(99) { record(typically) }
            record(worst)
        }.timing()

    private fun runsAt(scale: Double, of: List<Int> = spreadOut, plan: Plan = paying): Runs =
        Runs(of.map { runOf((it * scale).milliseconds, plan = plan) })

    @Test
    fun `a fifth slower reports an interval that contains the difference`() {
        val difference = runsAt(scale = 1.2).against(runsAt(scale = 1.0), p99(pay))

        difference.ratio shouldBe (1.2 plusOrMinus 0.03)
        val interval = difference.interval.shouldNotBeNull()
        withClue("the bootstrap produced $interval, which should hold the fifth that was injected") {
            (1.2 in interval) shouldBe true
        }
    }

    @Test
    fun `two identical sets of runs report an interval that contains no change at all`() {
        val difference = runsAt(scale = 1.0).against(runsAt(scale = 1.0), p99(pay))

        difference.ratio shouldBe (1.0 plusOrMinus 0.01)
        val interval = difference.interval.shouldNotBeNull()
        withClue("the bootstrap produced $interval, which should hold no change at all") {
            (1.0 in interval) shouldBe true
        }
    }

    @Test
    fun `fewer than five runs a side cannot tell, and says how many there were`() {
        val difference = runsAt(scale = 1.2, of = listOf(98, 100, 102)).against(runsAt(scale = 1.0), p99(pay))

        val verdict = difference.verdict.shouldBeInstanceOf<Tell.CannotTell>()
        verdict.why shouldContain "3 runs"
        verdict.wouldChangeIt shouldContain "five"
        difference.interval shouldBe null
    }

    @Test
    fun `the same two sets of runs compared twice reach the same interval`() {
        val once = runsAt(scale = 1.2).against(runsAt(scale = 1.0), p99(pay))
        val again = runsAt(scale = 1.2).against(runsAt(scale = 1.0), p99(pay))

        again.interval shouldBe once.interval
        again.verdict shouldBe once.verdict
    }

    @Test
    fun `runs of another plan are not compared at all`() {
        val other = paying.copy(steps = listOf("browse"))

        val difference = runsAt(scale = 1.0).against(runsAt(scale = 1.0, plan = other), p99(pay))

        difference.verdict.shouldBeInstanceOf<Tell.CannotTell>().why shouldContain "not asked to do the same thing"
    }

    @Test
    fun `a fifth slower is worse than the three percent that was declared acceptable`() {
        val difference = runsAt(scale = 1.2).against(runsAt(scale = 1.0), p99(pay), acceptable = 3.percent)

        difference.verdict shouldBe Tell.Worse
    }

    @Test
    fun `a fifth faster is better than the three percent that was declared acceptable`() {
        val difference = runsAt(scale = 1.0).against(runsAt(scale = 1.2), p99(pay), acceptable = 3.percent)

        difference.verdict shouldBe Tell.Better
    }

    @Test
    fun `a change smaller than the threshold cannot be told from one nobody would act on`() {
        val difference = runsAt(scale = 1.01).against(runsAt(scale = 1.0), p99(pay), acceptable = 10.percent)

        val verdict = difference.verdict.shouldBeInstanceOf<Tell.CannotTell>()
        verdict.why shouldContain "10"
        verdict.wouldChangeIt shouldContain "smaller threshold"
    }

    @Test
    fun `an interval that spans the threshold cannot tell, and more runs would narrow it`() {
        val difference = runsAt(scale = 1.05).against(runsAt(scale = 1.0), p99(pay), acceptable = 5.percent)

        val verdict = difference.verdict.shouldBeInstanceOf<Tell.CannotTell>()
        verdict.wouldChangeIt shouldContain "more runs"
    }

    @Test
    fun `the threshold can be declared afterwards, by whoever is asking`() {
        val difference = runsAt(scale = 1.2).against(runsAt(scale = 1.0), p99(pay))

        difference.judgedAt(3.percent) shouldBe Tell.Worse
        difference.judgedAt(50.percent).shouldBeInstanceOf<Tell.CannotTell>()
    }

    @Test
    fun `goodput collapsing is worse, though its ratio went down rather than up`() {
        val fast = Runs(spreadOut.map { runOf(it.milliseconds) })
        val slow = Runs(spreadOut.map { runOf((it * 10).milliseconds) })

        val difference = slow.against(fast, goodput(pay, under = 200.milliseconds), acceptable = 3.percent)

        withClue("every request met 200 ms before and none of them did after") {
            difference.ratio shouldBe (0.0 plusOrMinus 0.001)
        }
        difference.statistic.described shouldContain "goodput"
        difference.verdict shouldBe Tell.Worse
    }

    @Test
    fun `a statistic no run measured is not compared`() {
        val difference = runsAt(scale = 1.0).against(runsAt(scale = 1.0), p99(step("browse")))

        difference.verdict.shouldBeInstanceOf<Tell.CannotTell>().why shouldContain "browse"
    }

    @Test
    fun `a two percent difference on a machine that cannot resolve six percent is not a difference`() {
        val floor = Floor(resolution = 0.061, hiccups = Timing.none)

        val difference = runsAt(scale = 1.02).against(runsAt(scale = 1.0), p99(pay), floor = floor)

        val verdict = difference.verdict.shouldBeInstanceOf<Tell.CannotTell>()
        verdict.why shouldContain "6.1%"
        verdict.wouldChangeIt shouldContain "quieter machine"
        difference.interval shouldBe null
    }

    @Test
    fun `a change smaller than the machine's own stalls is the machine`() {
        val floor = Floor(resolution = 0.01, hiccups = stallsOf(37.milliseconds))

        val difference = runsAt(scale = 1.04).against(runsAt(scale = 1.0), p99(pay), floor = floor)

        val verdict = difference.verdict.shouldBeInstanceOf<Tell.CannotTell>()
        withClue("the p99 moved by about 4 ms and this machine stalls for 37: ${verdict.why}") {
            verdict.why shouldContain "37"
        }
        verdict.wouldChangeIt shouldContain "quieter machine"
    }

    @Test
    fun `a claim at the median clears the stalls at the median, not the ones at the tail`() {
        val floor = Floor(resolution = 0.01, hiccups = stallsOf(37.milliseconds, typically = 1.milliseconds))

        val difference = runsAt(scale = 1.04).against(runsAt(scale = 1.0), p50(pay), floor = floor)

        withClue("a stall one request in a hundred waits for cannot have moved a median") {
            difference.interval.shouldNotBeNull()
        }
    }

    @Test
    fun `a floor too coarse to be a fraction of this claim does not refuse it`() {
        // What a loaded machine reports: 125% of a null step whose median is
        // tens of microseconds, which is 62us of movement and says nothing
        // about a target at 100 ms.
        val loaded = Floor(resolution = 1.25, hiccups = stallsOf(7.milliseconds), probe = Probe(50.microseconds))

        val difference = runsAt(scale = 1.2).against(runsAt(scale = 1.0), p99(pay), 3.percent, loaded)

        difference.verdict shouldBe Tell.Worse
    }

    /**
     * The same floor, the same machine, two claims. Unusable is a property of
     * what is being claimed rather than of the machine, and a page that refused
     * everything on this floor would be refusing the 100 ms claim above.
     */
    @Test
    fun `a machine that moves by more than two fifths of the claim cannot support that claim`() {
        val loaded = Floor(resolution = 1.25, hiccups = Timing.none, probe = Probe(50.microseconds))
        val tiny = spreadOut.map { it.microseconds }

        val difference = Runs(tiny.map { runOf(it * 1.2) })
            .against(Runs(tiny.map { runOf(it) }), p99(pay), 3.percent, loaded)

        val verdict = difference.verdict.shouldBeInstanceOf<Tell.CannotTell>()
        withClue("62us of movement against a claim about 102us: ${verdict.why}") {
            verdict.why shouldContain "cannot support a claim"
        }
    }

    @Test
    fun `a share of requests is not a length of time, so a floor measured in durations cannot refuse it`() {
        val loaded = Floor(resolution = 1.25, hiccups = stallsOf(37.milliseconds), probe = Probe(50.microseconds))
        val fast = Runs(spreadOut.map { runOf(it.milliseconds) })
        val slow = Runs(spreadOut.map { runOf((it * 10).milliseconds) })

        slow.against(fast, goodput(pay, under = 200.milliseconds), 3.percent, loaded).verdict shouldBe Tell.Worse
    }

    @Test
    fun `a comparison across two machines carries the caveat that it may be the runner`() {
        val there = Runs(spreadOut.map { runOf(it.milliseconds).copy(machine = here.copy(cores = 2)) })

        runsAt(scale = 1.2).against(there, p99(pay)).caveat shouldContain "may be the runner"
        runsAt(scale = 1.2).against(runsAt(scale = 1.0), p99(pay)).caveat shouldBe null
    }
}

private const val SAMPLES = 100
