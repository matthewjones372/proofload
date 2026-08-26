package io.github.matthewjones372.kestrel.baseline

import io.github.matthewjones372.kestrel.Change
import io.github.matthewjones372.kestrel.Comparison
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.InjectionProfile
import io.github.matthewjones372.kestrel.Machine
import io.github.matthewjones372.kestrel.Plan
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random
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
    ): RunResult {
        val timing = Histogram()
            .apply { repeat(samples) { record(seeded.nextLong(range.first, range.last).milliseconds) } }
            .timing()
        return RunResult(
            startedAt = Instant.parse("2026-08-26T09:00:00Z"),
            steps = mapOf(
                name to StepStats(
                    name = name,
                    count = samples.toLong(),
                    ok = samples - 3L,
                    failures = mapOf("status 503" to 3L),
                    serviceTime = timing,
                    responseTime = timing,
                ),
            ),
            behind = Histogram().timing(),
            plan = plan,
            machine = machine,
        )
    }

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
    fun `a baseline from a future version is refused rather than guessed at`() {
        shouldThrow<IllegalArgumentException> { parseBaseline("kestrel-baseline\t99\nrun\t2026-08-26T09:00:00Z\n") }
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
}
