package io.github.matthewjones372.proofload

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ComparisonTest {

    private val seeded = Random(20260826)

    private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

    private fun planAt(rate: Rate, scenario: String = "paying"): Plan =
        Plan(scenario = scenario, steps = listOf("pay"), profile = constantRate(rate, over = 2.seconds))

    private fun planDrawing(vararg shapes: Shape): Plan {
        val plan = planAt(100.perSecond)
        return plan.copy(arms = plan.arms.map { it.copy(drawn = shapes.toList()) })
    }

    private fun planOfBoth(browsingAt: Rate = 20.perSecond): Plan = Plan(
        arms = listOf(
            PlannedArm("paying", listOf("pay"), constantRate(100.perSecond, over = 2.seconds)),
            PlannedArm("browsing", listOf("browse"), constantRate(browsingAt, over = 2.seconds)),
        ),
    )

    private fun runOf(
        steps: Map<String, LongRange>,
        samples: Int = 500,
        plan: Plan = Plan.none,
        machine: Machine = here,
        probe: Probe? = null,
    ): RunResult = RunResult(
        startedAt = Instant.parse("2026-08-26T09:00:00Z"),
        steps = steps.mapValues { (name, range) ->
            val timing = Histogram()
                .apply { repeat(samples) { record(seeded.nextLong(range.first, range.last).milliseconds) } }
                .timing()
            StepStats(name, Outcome(timing, timing), Outcome.none, timing, timing)
        },
        behind = Histogram().timing(),
        plan = plan,
        machine = machine,
        probe = probe,
    )

    private fun RunResult.changesAgainst(baseline: RunResult): List<Change> =
        against(baseline).shouldBeInstanceOf<Comparison.Compared>().changes

    private fun timingOf(range: LongRange, samples: Int = 500): Timing = Histogram()
        .apply { repeat(samples) { record(seeded.nextLong(range.first, range.last).milliseconds) } }
        .timing()

    /** A run whose two clocks disagree, which is every run where the generator fell behind. */
    private fun runOfBothClocks(step: String, service: LongRange, response: LongRange): RunResult {
        val serviceTime = timingOf(service)
        val responseTime = timingOf(response)
        return RunResult(
            startedAt = Instant.parse("2026-08-26T09:00:00Z"),
            steps = mapOf(
                step to StepStats(step, Outcome(serviceTime, responseTime), Outcome.none, serviceTime, responseTime),
            ),
            behind = Histogram().timing(),
            plan = Plan.none,
            machine = here,
        )
    }

    @Test
    fun `a comparison reads the clock it was asked for`() {
        val before = runOfBothClocks("pay", service = 80L..320L, response = 80L..320L)
        val after = runOfBothClocks("pay", service = 80L..320L, response = 800L..1200L)

        withClue("response time moved, and it is the default") {
            after.against(before).shouldBeInstanceOf<Comparison.Compared>()
                .changes.single().shouldBeInstanceOf<Change.Worse>()
        }
        withClue("service time did not, and that is the clock the target is on") {
            after.against(before, of = Clock.ServiceTime).shouldBeInstanceOf<Comparison.Compared>()
                .changes.single().shouldBeInstanceOf<Change.Indistinguishable>()
        }
    }

    @Test
    fun `a comparison says where it was read rather than leaving a page to assume`() {
        val before = runOf(mapOf("pay" to 80L..320L))
        val after = runOf(mapOf("pay" to 80L..320L))

        after.against(before).shouldBeInstanceOf<Comparison.Compared>().readAt shouldBe "p99 of response time"
        after.against(before, percentile = 50.0, of = Clock.ServiceTime)
            .shouldBeInstanceOf<Comparison.Compared>().readAt shouldBe "p50 of service time"
        after.against(before, percentile = 99.9).shouldBeInstanceOf<Comparison.Compared>()
            .percentileNamed shouldBe "p99.9"
    }

    @Test
    fun `two runs of the same target have not been shown to differ`() {
        val change = runOf(mapOf("pay" to 80L..320L)).changesAgainst(runOf(mapOf("pay" to 80L..320L))).single()

        change.shouldBeInstanceOf<Change.Indistinguishable>()
    }

    @Test
    fun `a run three times slower is worse, and says by how much`() {
        val change = runOf(mapOf("pay" to 800L..1200L)).changesAgainst(runOf(mapOf("pay" to 80L..320L))).single()

        val worse = change.shouldBeInstanceOf<Change.Worse>()
        (worse.now > worse.before) shouldBe true
    }

    @Test
    fun `a run three times faster is better rather than merely different`() {
        runOf(mapOf("pay" to 20L..40L)).changesAgainst(runOf(mapOf("pay" to 800L..1200L)))
            .single()
            .shouldBeInstanceOf<Change.Better>()
    }

    @Test
    fun `a step the baseline never had is named as new`() {
        val change = runOf(mapOf("pay" to 80L..120L)).changesAgainst(runOf(emptyMap())).single()

        change.shouldBeInstanceOf<Change.Added>()
        change.step shouldBe "pay"
    }

    @Test
    fun `a step that stopped running is named, because that is the interesting one`() {
        runOf(emptyMap()).changesAgainst(runOf(mapOf("pay" to 80L..120L)))
            .single()
            .shouldBeInstanceOf<Change.Gone>()
    }

    @Test
    fun `every step is compared, in a stable order`() {
        val now = runOf(mapOf("pay" to 80L..120L, "browse" to 10L..20L))
        val before = runOf(mapOf("browse" to 10L..20L, "cart" to 30L..40L))

        now.changesAgainst(before).map { it.step } shouldContainExactly listOf("browse", "cart", "pay")
    }

    @Test
    fun `a run compares as no different from itself`() {
        val run = runOf(mapOf("pay" to 80L..320L))

        run.changesAgainst(run).single().shouldBeInstanceOf<Change.Indistinguishable>()
    }

    @Test
    fun `a smoke run and a soak are not compared at all, and the profile is named`() {
        val soak = runOf(mapOf("pay" to 80L..120L), plan = planAt(500.perSecond))
        val smoke = runOf(mapOf("pay" to 80L..120L), plan = planAt(10.perSecond))

        val why = soak.against(smoke).shouldBeInstanceOf<Comparison.NotComparable>().why

        withClue(why) {
            why shouldContain "profile"
            why shouldContain "500.0"
        }
    }

    @Test
    fun `a run of another scenario is refused by name rather than compared step by step`() {
        val paying = runOf(mapOf("pay" to 80L..120L), plan = planAt(100.perSecond, scenario = "paying"))
        val browsing = runOf(mapOf("pay" to 80L..120L), plan = planAt(100.perSecond, scenario = "browsing"))

        val why = paying.against(browsing).shouldBeInstanceOf<Comparison.NotComparable>().why

        withClue(why) { why shouldContain "browsing" }
    }

    @Test
    fun `a mix is not compared against a run of one of its arms, and says which arm is missing`() {
        val mixed = runOf(mapOf("pay" to 80L..120L), plan = planOfBoth())
        val alone = runOf(mapOf("pay" to 80L..120L), plan = planAt(100.perSecond))

        val why = mixed.against(alone).shouldBeInstanceOf<Comparison.NotComparable>().why

        withClue(why) {
            why shouldContain "browsing"
            why shouldContain "was not before"
        }
    }

    @Test
    fun `a mix compares against the same mix, arm for arm`() {
        val now = runOf(mapOf("pay" to 80L..120L), plan = planOfBoth())
        val before = runOf(mapOf("pay" to 80L..120L), plan = planOfBoth())

        now.against(before).shouldBeInstanceOf<Comparison.Compared>()
    }

    @Test
    fun `an arm sent at another rate names the arm as well as the rate`() {
        val faster = runOf(mapOf("pay" to 80L..120L), plan = planOfBoth(browsingAt = 500.perSecond))
        val slower = runOf(mapOf("pay" to 80L..120L), plan = planOfBoth())

        val why = faster.against(slower).shouldBeInstanceOf<Comparison.NotComparable>().why

        withClue(why) {
            why shouldContain "arm \"browsing\" profile"
            why shouldContain "500.0"
        }
    }

    @Test
    fun `two runs drawn at different skews are refused rather than reported as a regression`() {
        val heavy = runOf(mapOf("pay" to 80L..120L), plan = planDrawing(Shape("zipf(keys=1000000, skew=1.1)", 0L)))
        val flat = runOf(mapOf("pay" to 80L..120L), plan = planDrawing(Shape("zipf(keys=1000000, skew=0.8)", 0L)))

        val why = heavy.against(flat).shouldBeInstanceOf<Comparison.NotComparable>().why

        withClue(why) {
            why shouldContain "drawn"
            why shouldContain "skew=0.8"
            why shouldContain "skew=1.1"
        }
    }

    @Test
    fun `the same shape drawn from another seed is refused, since the keys asked for were not the same`() {
        val one = runOf(mapOf("pay" to 80L..120L), plan = planDrawing(Shape("uuids()", seed = 1L)))
        val other = runOf(mapOf("pay" to 80L..120L), plan = planDrawing(Shape("uuids()", seed = 2L)))

        one.against(other).shouldBeInstanceOf<Comparison.NotComparable>()
    }

    @Test
    fun `two runs drawn the same way compare`() {
        val plan = planDrawing(Shape("zipf(keys=1000000, skew=1.1)", 0L))
        val now = runOf(mapOf("pay" to 80L..120L), plan = plan)
        val before = runOf(mapOf("pay" to 80L..120L), plan = plan)

        now.against(before).shouldBeInstanceOf<Comparison.Compared>()
    }

    @Test
    fun `a baseline that declared no shape compares against a run that drew one, and against one that did not`() {
        val declared = runOf(mapOf("pay" to 80L..120L), plan = planDrawing(Shape("zipf(keys=1000000, skew=1.1)", 0L)))
        val silent = runOf(mapOf("pay" to 80L..120L), plan = planAt(100.perSecond))

        withClue("every stored baseline predates the field, so an absent shape is no claim to refuse across") {
            declared.against(silent).shouldBeInstanceOf<Comparison.Compared>()
            silent.against(declared).shouldBeInstanceOf<Comparison.Compared>()
            silent.against(silent).shouldBeInstanceOf<Comparison.Compared>()
        }
    }

    @Test
    fun `the same plan on a bigger machine is still compared, with the runner named as the caveat`() {
        val plan = planAt(100.perSecond)
        val onEight = runOf(mapOf("pay" to 80L..120L), plan = plan, machine = here)
        val onFour = runOf(mapOf("pay" to 80L..120L), plan = plan, machine = here.copy(cores = 4))

        val compared = onEight.against(onFour).shouldBeInstanceOf<Comparison.Compared>()

        compared.changes.shouldNotBeEmpty()
        withClue("a different machine warns rather than refuses") {
            compared.caveat.shouldNotBeNull() shouldContain "may be the runner"
        }
    }

    @Test
    fun `a run on a machine half as fast names the runner before it names a step`() {
        val plan = planAt(100.perSecond)
        val baseline = runOf(mapOf("pay" to 80L..120L), plan = plan, probe = Probe(50.microseconds))
        val slower = runOf(mapOf("pay" to 800L..1200L), plan = plan, probe = Probe(100.microseconds))

        val compared = slower.against(baseline).shouldBeInstanceOf<Comparison.Compared>()

        compared.slowdown shouldBe 2.0
        val caveat = compared.caveat.shouldNotBeNull()
        withClue(caveat) {
            caveat shouldStartWith "this machine ran a fixed probe 2.00 times slower"
            caveat shouldContain "100us"
            caveat shouldContain "50us"
        }
    }

    @Test
    fun `a probe that landed where the baseline's did leaves the steps to answer for themselves`() {
        val plan = planAt(100.perSecond)
        val baseline = runOf(mapOf("pay" to 80L..120L), plan = plan, probe = Probe(50.microseconds))
        val now = runOf(mapOf("pay" to 80L..120L), plan = plan, probe = Probe(52.microseconds))

        val compared = now.against(baseline).shouldBeInstanceOf<Comparison.Compared>()

        compared.caveat shouldBe null
    }

    @Test
    fun `a baseline nobody probed is compared without a claim about the runner`() {
        val plan = planAt(100.perSecond)
        val baseline = runOf(mapOf("pay" to 80L..120L), plan = plan)
        val now = runOf(mapOf("pay" to 80L..120L), plan = plan, probe = Probe(100.microseconds))

        val compared = now.against(baseline).shouldBeInstanceOf<Comparison.Compared>()

        compared.slowdown shouldBe null
        compared.caveat shouldBe null
    }

    @Test
    fun `the runner is named on a machine that reads the same as the baseline's, which is the whole case`() {
        val plan = planAt(100.perSecond)
        val baseline = runOf(mapOf("pay" to 80L..120L), plan = plan, machine = here, probe = Probe(40.microseconds))
        val now = runOf(mapOf("pay" to 80L..120L), plan = plan, machine = here, probe = Probe(90.microseconds))

        val compared = now.against(baseline).shouldBeInstanceOf<Comparison.Compared>()

        withClue("two hosted runners of the same spec are the same Machine and not the same speed") {
            compared.caveat.shouldNotBeNull() shouldContain "fixed probe"
        }
    }

    @Test
    fun `no baseline at all is reported rather than quietly skipped`() {
        val why = runOf(mapOf("pay" to 80L..120L)).against(null)
            .shouldBeInstanceOf<Comparison.NotComparable>()
            .why

        withClue(why) { why shouldContain "no baseline" }
    }

    @Test
    fun `two runs on the one machine carry no caveat to distrust them by`() {
        val plan = planAt(100.perSecond)

        val compared = runOf(mapOf("pay" to 80L..120L), plan = plan)
            .against(runOf(mapOf("pay" to 80L..120L), plan = plan))
            .shouldBeInstanceOf<Comparison.Compared>()

        compared.caveat shouldBe null
    }
}
