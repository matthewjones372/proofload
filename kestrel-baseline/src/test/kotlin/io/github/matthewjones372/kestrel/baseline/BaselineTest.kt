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
import io.github.matthewjones372.kestrel.Said
import io.github.matthewjones372.kestrel.Shard
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.WarmUp
import io.github.matthewjones372.kestrel.against
import io.github.matthewjones372.kestrel.arrivalsFrom
import io.github.matthewjones372.kestrel.constantRate
import io.github.matthewjones372.kestrel.departures
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.rampRate
import io.github.matthewjones372.kestrel.randomized
import io.github.matthewjones372.kestrel.replaying
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
                    failed = Outcome(failed.timing(), failed.timing(), mapOf(Said("status 503") to FAILURES.toLong())),
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
            why shouldContain "5"
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
            .replaceFirst("kestrel-baseline\t6", "kestrel-baseline\t3")

        parseBaseline(older).probe shouldBe null
    }

    @Test
    fun `the warm-up travels, so a warmed run is comparable to a warmed baseline`(@TempDir dir: Path) {
        val plan = planOf(constantRate(100.perSecond, over = 2.seconds)).copy(warmUp = WarmUp(5.seconds))

        val read = runOf(plan = plan).throughAFile(dir)

        read.plan.warmUp shouldBe WarmUp(5.seconds)
        runOf(plan = plan).against(read).shouldBeInstanceOf<Comparison.Compared>()
    }

    @Test
    fun `a warmed run is refused against a baseline that warmed nothing`(@TempDir dir: Path) {
        val cold = runOf(plan = planOf(constantRate(100.perSecond, over = 2.seconds)))
        cold.writeBaseline(dir.resolve("b.kestrel"))

        val warmed = runOf(
            plan = planOf(constantRate(100.perSecond, over = 2.seconds)).copy(warmUp = WarmUp(5.seconds)),
        )
        val why = warmed.against(readBaseline(dir.resolve("b.kestrel")))
            .shouldBeInstanceOf<Comparison.NotComparable>()
            .why

        withClue(why) { why shouldContain "warm-up" }
    }

    @Test
    fun `the lateness travels, so a shard's schedule can be judged where it is merged`(@TempDir dir: Path) {
        val late = Histogram().apply { repeat(200) { record(seeded.nextLong(1L, 90L).milliseconds) } }
        val run = runOf().copy(behind = late.timing())

        val read = run.throughAFile(dir)

        withClue("a merged run reads its lateness from the worst injector, which needs it in the file") {
            read.behind.p99 shouldBe run.behind.p99
            read.behind.count shouldBe run.behind.count
        }
    }

    @Test
    fun `the injector's own stalls travel, so a tail can still be laid beside them`(@TempDir dir: Path) {
        val stalls = Histogram().apply { repeat(40) { record(seeded.nextLong(1L, 12L).milliseconds) } }

        val read = runOf().copy(hiccups = stalls.timing()).throughAFile(dir)

        read.hiccups.p99 shouldBe stalls.timing().p99
    }

    @Test
    fun `a shard says which injector of how many wrote it, and when they all started`(@TempDir dir: Path) {
        val shard = Shard(index = 2, of = 4, startingAt = Instant.parse("2026-08-26T09:00:00Z"))

        val read = runOf().copy(shard = shard).throughAFile(dir)

        read.shard shouldBe shard
    }

    @Test
    fun `a run nobody sharded writes no shard line, rather than injector zero of one`(@TempDir dir: Path) {
        val read = runOf().throughAFile(dir)

        withClue("zero of one is a claim about a distributed run that never happened") {
            read.shard shouldBe null
        }
    }

    @Test
    fun `a version 5 baseline reads, and claims no lateness, stalls or shard`() {
        // What a version 5 writer produced: every line this one writes except
        // the three it did not know about.
        val older = runOf(plan = planOf(constantRate(100.perSecond, over = 2.seconds)))
            .copy(behind = Histogram().apply { record(41.milliseconds) }.timing())
            .asBaseline()
            .lineSequence()
            .filterNot { it.split("\t").first() in setOf("behind", "stalls", "shard") }
            .joinToString(separator = "\n")
            .replaceFirst("kestrel-baseline\t6", "kestrel-baseline\t5")

        val read = parseBaseline(older)

        withClue("version 5 wrote no lateness at all, so a reader must not invent one") {
            read.behind shouldBe Timing.none
            read.hiccups shouldBe Timing.none
            read.shard shouldBe null
        }
    }

    @Test
    fun `a version 4 baseline reads, and claims no warm-up`() {
        val older = runOf(plan = planOf(constantRate(100.perSecond, over = 2.seconds)))
            .asBaseline()
            .replaceFirst("kestrel-baseline\t6", "kestrel-baseline\t4")

        parseBaseline(older).plan.warmUp shouldBe null
    }

    @Test
    fun `a replay travels as what it was, so a run compares against its own baseline`(@TempDir dir: Path) {
        val capture = arrivalsFrom(
            (0..99).map { Instant.parse("2026-08-26T12:00:00Z").plusMillis(it * 37L) },
            source = "friday-peak.csv",
        )
        val plan = planOf(capture.replaying(scaled = 2.0))

        val read = runOf(plan = plan).throughAFile(dir)

        withClue("the file carries what the capture was, and no timestamps") {
            read.plan.profile shouldBe plan.profile
            runOf(plan = plan).against(read).shouldBeInstanceOf<Comparison.Compared>()
        }
    }

    @Test
    fun `a run replayed from another capture is refused rather than compared`(@TempDir dir: Path) {
        val noon = Instant.parse("2026-08-26T12:00:00Z")
        val friday = arrivalsFrom((0..99).map { noon.plusMillis(it * 37L) }, source = "friday.csv")
        val monday = arrivalsFrom((0..99).map { noon.plusMillis(it * 51L) }, source = "monday.csv")

        runOf(plan = planOf(friday.replaying())).writeBaseline(dir.resolve("b.kestrel"))

        val why = runOf(plan = planOf(monday.replaying()))
            .against(readBaseline(dir.resolve("b.kestrel")))
            .shouldBeInstanceOf<Comparison.NotComparable>()
            .why

        withClue(why) { why shouldContain "monday.csv" }
    }

    @Test
    fun `a capture read back from a baseline refuses to be replayed`(@TempDir dir: Path) {
        val capture = arrivalsFrom(
            (0..99).map { Instant.parse("2026-08-26T12:00:00Z").plusMillis(it * 37L) },
            source = "friday-peak.csv",
        )
        val read = runOf(plan = planOf(capture.replaying())).throughAFile(dir)

        val why = shouldThrow<IllegalArgumentException> {
            (read.plan.profile as InjectionProfile.Replay).departures().toList()
        }

        withClue(why.message.orEmpty()) {
            why.message.orEmpty() shouldContain "records what a capture was and not its arrivals"
        }
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
