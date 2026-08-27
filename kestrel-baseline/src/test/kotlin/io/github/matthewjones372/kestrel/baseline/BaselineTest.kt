package io.github.matthewjones372.kestrel.baseline

import io.github.matthewjones372.kestrel.Change
import io.github.matthewjones372.kestrel.Comparison
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.InjectionProfile
import io.github.matthewjones372.kestrel.Machine
import io.github.matthewjones372.kestrel.Outcome
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.Probe
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.against
import io.github.matthewjones372.kestrel.constantRate
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.rampRate
import io.github.matthewjones372.kestrel.randomized
import io.github.matthewjones372.kestrel.then
import io.github.matthewjones372.kestrel.timing
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BaselineTest {

    private val seeded = Random(20260826)

    private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

    private fun planOf(profile: InjectionProfile) = Plan(scenario = "paying", steps = listOf("pay"), profile = profile)

    private fun runOf(
        name: String = "pay",
        range: LongRange = 80L..320L,
        samples: Int = 500,
        plan: Plan = Plan.none,
        machine: Machine = here,
        probe: Probe? = null,
    ): RunResult {
        val ok = histogramOf(range, samples - FAILURES)
        val failed = histogramOf(range, FAILURES)
        val whole = Histogram().apply { merge(ok); merge(failed) }.timing()
        return RunResult(
            startedAt = Instant.parse("2026-08-26T09:00:00Z"),
            steps = mapOf(
                name to StepStats(
                    name = name,
                    ok = Outcome(ok.timing(), ok.timing()),
                    failed = Outcome(failed.timing(), failed.timing(), mapOf("status 503" to FAILURES.toLong())),
                    serviceTime = whole,
                    responseTime = whole,
                ),
            ),
            behind = Histogram().timing(),
            plan = plan,
            machine = machine,
            probe = probe,
        )
    }

    private fun histogramOf(range: LongRange, samples: Int) =
        Histogram().apply { repeat(samples) { record(seeded.nextLong(range.first, range.last).milliseconds) } }

    private fun RunResult.throughAFile(dir: Path): RunResult {
        writeBaseline(dir.resolve("b.kestrel"))
        return readBaseline(dir.resolve("b.kestrel"))
    }

    @Test
    fun `a run written and read back is the same run to compare against`(@TempDir dir: Path) {
        val run = runOf()
        run.writeBaseline(dir.resolve("baseline.kestrel"))

        val read = readBaseline(dir.resolve("baseline.kestrel"))

        read["pay"].count shouldBe run["pay"].count
        read["pay"].failed.count shouldBe run["pay"].failed.count
        read["pay"].responseTime.p99 shouldBe run["pay"].responseTime.p99
        run.against(read).shouldBeInstanceOf<Comparison.Compared>().changes
            .single()
            .shouldBeInstanceOf<Change.Indistinguishable>()
    }

    @Test
    fun `the buckets travel, because an interval cannot be rebuilt from five numbers`(@TempDir dir: Path) {
        val run = runOf()
        run.writeBaseline(dir.resolve("b.kestrel"))

        val read = readBaseline(dir.resolve("b.kestrel"))

        read["pay"].responseTime.distribution shouldBe run["pay"].responseTime.distribution
    }

    @Test
    fun `a slower run compares as worse against what was kept`(@TempDir dir: Path) {
        runOf(range = 80L..120L).writeBaseline(dir.resolve("b.kestrel"))

        val comparison = runOf(range = 800L..1_200L).against(readBaseline(dir.resolve("b.kestrel")))

        comparison.shouldBeInstanceOf<Comparison.Compared>().changes
            .single()
            .shouldBeInstanceOf<Change.Worse>()
    }

    @Test
    fun `a step named with a tab cannot split a line`(@TempDir dir: Path) {
        val awkward = runOf(name = "pay\tnow")
        awkward.writeBaseline(dir.resolve("b.kestrel"))

        readBaseline(dir.resolve("b.kestrel")).steps.keys shouldBe setOf("pay\tnow")
    }

    @Test
    fun `a file that is not a baseline says so rather than half-reading it`() {
        shouldThrow<IllegalArgumentException> { parseBaseline("some other file\nentirely\n") }
    }

    @Test
    fun `a baseline from a future version is refused, and the refusal names the version`() {
        val why = shouldThrow<IllegalArgumentException> {
            parseBaseline("kestrel-baseline\t99\nrun\t2026-08-26T09:00:00Z\n")
        }.message.orEmpty()

        withClue(why) {
            why shouldContain "version 99"
            why shouldContain "4"
        }
    }

    @Test
    fun `the probe travels, so a runner half as fast is named before a step is`(@TempDir dir: Path) {
        val plan = planOf(constantRate(100.perSecond, over = 2.seconds))
        runOf(plan = plan, probe = Probe(50.microseconds)).writeBaseline(dir.resolve("b.kestrel"))

        val compared = runOf(plan = plan, probe = Probe(100.microseconds))
            .against(readBaseline(dir.resolve("b.kestrel")))
            .shouldBeInstanceOf<Comparison.Compared>()

        compared.slowdown shouldBe 2.0
        withClue(compared.caveat.orEmpty()) { compared.caveat.shouldNotBeNull() shouldContain "fixed probe" }
    }

    @Test
    fun `a baseline written by the version before this one reads, and claims no probe`() {
        val older = runOf(plan = planOf(constantRate(100.perSecond, over = 2.seconds)))
            .asBaseline()
            .replaceFirst("kestrel-baseline\t4", "kestrel-baseline\t3")

        parseBaseline(older).probe shouldBe null
    }

    @Test
    fun `the plan travels, so the next run can be refused rather than compared to unlike work`(@TempDir dir: Path) {
        val smoke = runOf(plan = planOf(constantRate(10.perSecond, over = 2.seconds)))
        smoke.writeBaseline(dir.resolve("b.kestrel"))

        val soak = runOf(plan = planOf(constantRate(500.perSecond, over = 2.seconds)))
        val why = soak.against(readBaseline(dir.resolve("b.kestrel")))
            .shouldBeInstanceOf<Comparison.NotComparable>()
            .why

        withClue(why) { why shouldContain "500.0" }
    }

    @Test
    fun `a run written and read back is still comparable to itself`(@TempDir dir: Path) {
        val run = runOf(plan = planOf(constantRate(100.perSecond, over = 2.seconds)))

        run.against(run.throughAFile(dir)).shouldBeInstanceOf<Comparison.Compared>()
    }

    @Test
    fun `every shape of rate line survives the round trip, or a stage would read as a change`(@TempDir dir: Path) {
        val shape = (constantRate(10.perSecond, over = 1.seconds) then rampRate(10.perSecond, 50.perSecond, 2.seconds))
            .randomized(seed = 7)

        runOf(plan = planOf(shape)).throughAFile(dir).plan.profile shouldBe shape
    }

    @Test
    fun `the machine travels, so a comparison can say the runner changed under it`(@TempDir dir: Path) {
        val baseline = runOf(
            plan = planOf(constantRate(100.perSecond, over = 2.seconds)),
            machine = here.copy(cores = 4),
        )
        baseline.writeBaseline(dir.resolve("b.kestrel"))

        val comparison = runOf(plan = planOf(constantRate(100.perSecond, over = 2.seconds)))
            .against(readBaseline(dir.resolve("b.kestrel")))

        comparison.shouldBeInstanceOf<Comparison.Compared>().before shouldBe here.copy(cores = 4)
    }

    /** Enough that a baseline has both sides to carry, and few enough that the percentiles stay the run's. */
    private companion object {
        const val FAILURES = 3
    }
}
