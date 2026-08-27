package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ComparisonTest {

    private val seeded = Random(20260826)

    private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

    private fun planAt(rate: Rate, scenario: String = "paying"): Plan =
        Plan(scenario = scenario, steps = listOf("pay"), profile = constantRate(rate, over = 2.seconds))

    private fun runOf(
        steps: Map<String, LongRange>,
        samples: Int = 500,
        plan: Plan = Plan.none,
        machine: Machine = here,
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
    )

    private fun RunResult.changesAgainst(baseline: RunResult): List<Change> =
        against(baseline).shouldBeInstanceOf<Comparison.Compared>().changes

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
    fun `two runs on the one machine carry no caveat to distrust them by`() {
        val plan = planAt(100.perSecond)

        val compared = runOf(mapOf("pay" to 80L..120L), plan = plan)
            .against(runOf(mapOf("pay" to 80L..120L), plan = plan))
            .shouldBeInstanceOf<Comparison.Compared>()

        compared.caveat shouldBe null
    }
}
