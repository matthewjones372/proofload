package io.github.matthewjones372.proofload.report

import io.github.matthewjones372.proofload.Arrivals
import io.github.matthewjones372.proofload.Change
import io.github.matthewjones372.proofload.Comparison
import io.github.matthewjones372.proofload.Floor
import io.github.matthewjones372.proofload.Headroom
import io.github.matthewjones372.proofload.Histogram
import io.github.matthewjones372.proofload.InjectionProfile
import io.github.matthewjones372.proofload.Interval
import io.github.matthewjones372.proofload.Limits
import io.github.matthewjones372.proofload.Machine
import io.github.matthewjones372.proofload.Outcome
import io.github.matthewjones372.proofload.Plan
import io.github.matthewjones372.proofload.PlannedArm
import io.github.matthewjones372.proofload.Probe
import io.github.matthewjones372.proofload.Reason
import io.github.matthewjones372.proofload.RunRecorder
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.Said
import io.github.matthewjones372.proofload.Second
import io.github.matthewjones372.proofload.Shape
import io.github.matthewjones372.proofload.StepStats
import io.github.matthewjones372.proofload.Timing
import io.github.matthewjones372.proofload.WarmUp
import io.github.matthewjones372.proofload.against
import io.github.matthewjones372.proofload.hold
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.randomized
import io.github.matthewjones372.proofload.timing
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MarkdownTest {

    private fun timingOf(values: List<Duration>): Timing =
        Histogram().apply { values.forEach { record(it) } }.timing()

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResource("/golden/$name")) { "no golden named $name" }.readText()

    /**
     * This against the golden [name], or a rewrite of it when the run was asked
     * for one with `-Dproofload.regenerate=true`.
     *
     * The rewrite fails rather than passes. A switch that regenerated and went
     * green would turn every later regression into a green build for whoever
     * left it on.
     */
    private infix fun String.matches(name: String) {
        if (System.getProperty("proofload.regenerate").toBoolean()) {
            val path = Path.of("src/test/resources/golden", name).toAbsolutePath()
            Files.writeString(path, this, Charsets.UTF_8)
            error("rewrote $path from this run. Re-run without -Dproofload.regenerate and read the diff.")
        }
        this shouldBe golden(name)
    }

    private fun step(
        name: String,
        ok: List<Duration>,
        failed: List<Duration> = emptyList(),
        reasons: Map<Reason, Long> = emptyMap(),
    ) = StepStats(
        name = name,
        ok = Outcome(timingOf(ok), timingOf(ok)),
        failed = Outcome(timingOf(failed), timingOf(failed), reasons),
        // The table prints response time; service time is carried so the value
        // stays a whole StepStats, not so the report reads it.
        serviceTime = timingOf(ok + failed),
        responseTime = timingOf(ok + failed),
    )

    private val browse =
        step("browse", List(4) { 1.milliseconds } + List(2) { 2.milliseconds } + List(4) { 3.milliseconds })

    /** Two fifths at 10 ms, two fifths at 20 ms and the rest at 30 ms: a p50 in the middle band, a p95 in the top. */
    private fun spread(samples: Int): List<Duration> {
        val fifth = samples / 5
        return List(fifth * 2) { 10.milliseconds } + List(fifth * 2) { 20.milliseconds } +
            List(samples - fifth * 4) { 30.milliseconds }
    }

    /**
     * Nine in ten at 10 ms and the rest at 2 s. Its p99 is that of a step which
     * is merely slow, and it is nothing of the kind — the shape is the only part
     * of a summary that can tell the two apart, which is why it is printed.
     */
    private val bimodal = step("checkout", List(900) { 10.milliseconds } + List(100) { 2.seconds })

    private val startedAt = Instant.parse("2026-08-26T09:00:00Z")

    @Test
    fun `the step table says how many users reached a step beside the requests they made`() {
        val result = RunResult(
            startedAt = startedAt,
            steps = mapOf("browse" to browse.copy(reached = 4L)),
            behind = Histogram().timing(),
        )

        val table = result.markdown()

        table shouldContain "| Step   | Requests | Reached |"
        table shouldContain "| browse |       10 |       4 |"
    }

    @Test
    fun `a result that never counted users prints no reaches rather than a zero nobody measured`() {
        val result = RunResult(startedAt = startedAt, steps = mapOf("browse" to browse), behind = Histogram().timing())

        result.markdown() shouldContain "| browse |       10 |       — |"
    }

    @Test
    fun `a bimodal step shows both modes, and the decade between them that counted nothing`() {
        val result = RunResult(
            startedAt = startedAt,
            steps = mapOf("checkout" to bimodal),
            behind = Histogram().timing(),
        )

        result.markdown() matches "bimodal.md"
    }

    @Test
    fun `every sample the step counted is in the bars, so no mode is quietly dropped`() {
        val result = RunResult(
            startedAt = startedAt,
            steps = mapOf("checkout" to bimodal),
            behind = Histogram().timing(),
        )

        val summary = result.markdown()
        val counted = summary.lines()
            .filter { it.contains('#') }
            .mapNotNull { it.trim().substringAfterLast(' ').toLongOrNull() }

        withClue(summary) { counted.sum() shouldBe bimodal.count }
    }

    @Test
    fun `a run that fell behind says so on the first line, above the table`() {
        val result = RunResult(
            startedAt = startedAt,
            steps = mapOf(
                "browse" to browse,
                "pay" to step(
                    "pay",
                    ok = spread(100).dropLast(3),
                    failed = List(3) { 30.milliseconds },
                    reasons = mapOf(Said("status 503") to 3L),
                ),
            ),
            behind = timingOf(listOf(100.milliseconds)),
        )

        result.markdown() matches "behind-schedule.md"
    }

    @Test
    fun `a backlog too small to move a printed number is not worth a warning`() {
        val result = RunResult(
            startedAt = startedAt,
            steps = mapOf(
                "browse" to browse,
                "pay" to step("pay", spread(100)),
            ),
            behind = timingOf(listOf(50.microseconds)),
        )

        result.markdown() matches "kept-up.md"
        result.markdown() shouldNotContain "Behind schedule"
    }

    @Test
    fun `a failure reason is arbitrary text, so pipes and tags cannot escape their cell`() {
        val reason = "unexpected `</td>` | status <500>"
        val hostile = step(
            "GET /a|b",
            ok = List(2) { 1.milliseconds },
            failed = List(3) { 1.milliseconds },
            reasons = mapOf(Said(reason) to 3L),
        )
        val result = RunResult(
            startedAt = startedAt,
            steps = mapOf("GET /a|b" to hostile),
            behind = timingOf(listOf(Duration.ZERO)),
        )

        result.markdown() matches "hostile-text.md"
    }

    @Test
    fun `a run that lost records says so above everything, and says which step lost them`() {
        val result = RunResult(
            startedAt = startedAt,
            steps = mapOf(
                "submitted" to step("submitted", spread(113)),
                "settled" to step("settled", spread(60)).copy(unmatched = 41L, inFlight = 12L),
            ),
            behind = timingOf(listOf(50.microseconds)),
        )

        result.markdown() matches "records-lost.md"
    }

    @Test
    fun `a run that lost nothing is not made to say it lost nothing`() {
        val result = RunResult(
            startedAt = startedAt,
            steps = mapOf("browse" to browse),
            behind = timingOf(listOf(50.microseconds)),
        )

        result.markdown() shouldNotContain "never arrived"
    }

    @Test
    fun `a run with no steps reports that rather than an empty table`() {
        val result = RunResult(startedAt = startedAt, steps = emptyMap(), behind = timingOf(listOf(Duration.ZERO)))

        result.markdown() shouldContain "No steps ran."
        result.markdown() shouldNotContain "| Step"
    }

    @Test
    fun `a result nobody planned has no arrival process to name`() {
        val result = RunResult(startedAt = startedAt, steps = mapOf("browse" to browse), behind = timingOf(nothing))

        result.markdown() shouldNotContain "Arrivals were"
    }

    @Test
    fun `an even run says so, and says what a reader should read into it`() {
        val result = ran(hold(200.perSecond, over = 10.seconds), Arrivals(2000L, 5.milliseconds, 0.0))

        result.markdown() shouldContain "Arrivals were evenly spaced, which understates queueing against the " +
            "same mean rate in production. Measured 5.00ms between departures, coefficient of variation 0.00."
    }

    @Test
    fun `a randomised run names the seed it was drawn from and the variation it produced`() {
        val shape = hold(200.perSecond, over = 10.seconds).randomized(seed = 20260826)

        val result = ran(shape, Arrivals(2000L, 5.milliseconds, 0.98))

        result.markdown() shouldContain "Arrivals were drawn from seed 20260826. Measured 5.00ms between " +
            "departures, coefficient of variation 0.98."
    }

    @Test
    fun `what the injector itself stalled for is printed beside the tail it could have caused`() {
        val result = RunResult(
            startedAt = startedAt,
            steps = mapOf("pay" to step("pay", spread(100))),
            behind = timingOf(listOf(50.microseconds)),
            hiccups = timingOf(
                List(95) { 1.milliseconds } + List(4) { 14.milliseconds } + listOf(30.milliseconds),
            ),
        )

        result.markdown() matches "hiccups.md"
    }

    @Test
    fun `a result nobody ran has no injector to have stalled`() {
        val result = RunResult(startedAt = startedAt, steps = mapOf("browse" to browse), behind = timingOf(nothing))

        result.markdown() shouldNotContain "stalled"
    }

    @Test
    fun `a calibrated machine says what it can resolve before it says what it measured`() {
        val floor = Floor(resolution = 0.061, hiccups = timingOf(List(9) { 1.milliseconds } + 14.milliseconds))
        val result = RunResult(startedAt = startedAt, steps = mapOf("browse" to browse), behind = timingOf(nothing))

        result.markdown(floor = floor) shouldContain "Calibrated on this machine: differences under 6.10% are not " +
            "resolvable here. The injector's own stalls reached 14.0ms at p99."
    }

    @Test
    fun `a machine too coarse to bound a claim says that instead of the number`() {
        val floor = Floor(resolution = 0.40, hiccups = Timing.none)
        val result = RunResult(startedAt = startedAt, steps = mapOf("browse" to browse), behind = timingOf(nothing))

        val markdown = result.markdown(floor = floor)
        markdown shouldContain "**This machine cannot support a latency claim.**"
        markdown shouldNotContain "are not resolvable here"
    }

    @Test
    fun `a job summary with no baseline says so rather than leaving the comparison out`() {
        val summary = compared().markdown(compared().against(null))

        summary shouldContain "Not compared to the last run."
        summary shouldContain "no baseline to compare against"
    }

    @Test
    fun `a run compared to the last one matches its golden`() {
        compared().markdown(
            Comparison.Compared(
                changes = listOf(
                    Change.Indistinguishable("browse", 3.milliseconds, 3.milliseconds),
                    Change.Worse("pay", 20.milliseconds, 30.milliseconds, Interval(28.milliseconds, 33.milliseconds)),
                ),
                before = here,
                now = here,
            ),
        ) matches "compared.md"
    }

    @Test
    fun `a runner half as fast is named above the steps it would otherwise be blamed on`() {
        val summary = compared().markdown(
            Comparison.Compared(
                changes = listOf(Change.Worse("pay", 20.milliseconds, 30.milliseconds, interval)),
                before = here,
                now = here,
                beforeProbe = Probe(50.microseconds),
                nowProbe = Probe(100.microseconds),
            ),
        )

        summary shouldContain "ran a fixed probe 2.00 times slower"
        withClue("a reader who stops after the first line must not stop at the step") {
            summary.indexOf("fixed probe") shouldBeLessThan summary.indexOf("| Step")
        }
    }

    @Test
    fun `a machine too coarse to bound a claim prints no comparison at all`() {
        val summary = compared().markdown(
            comparison = Comparison.Compared(
                changes = listOf(Change.Worse("pay", 20.milliseconds, 30.milliseconds, interval)),
                before = here,
                now = here,
            ),
            floor = Floor(resolution = 0.40, hiccups = Timing.none),
        )

        summary shouldContain "cannot support a latency claim"
        summary shouldNotContain "measurably changed"
    }

    @Test
    fun `the step table names the arm that sent each step`() {
        val summary = mixed().markdown()

        summary shouldContain "| Step   | Arm      |"
        summary shouldContain "| home   | browse   |"
        summary shouldContain "| cart   | checkout |"
    }

    @Test
    fun `a one-armed run carries no arm column, because a column of one repeated value is noise`() {
        val summary = ran(hold(200.perSecond, over = 10.seconds), Arrivals(2000L, 5.milliseconds, 0.0)).markdown()

        summary shouldNotContain "| Arm"
    }

    @Test
    fun `the mix names the ratio each arm was asked for beside the one that departed`() {
        val summary = mixed().markdown()

        // 160 of the 200 users planned browse, and 150 of the 195 counted took it.
        summary shouldContain "| browse   |           160 | 80.00% |   76.92% |"
        summary shouldContain "| checkout |            40 | 20.00% |   23.08% |"
    }

    @Test
    fun `a run that counted no users names what was asked for and says the rest was not measured`() {
        val uncounted = mixed().let { run ->
            run.copy(steps = run.steps.mapValues { (_, step) -> step.copy(reached = 0L) })
        }

        val summary = uncounted.markdown()

        summary shouldContain "| browse   |           160 | 80.00% |        — |"
        summary shouldContain "counted no users, so what departed cannot be split by arm"
    }

    @Test
    fun `a mix matches its golden`() {
        mixed().markdown() matches "mixed.md"
    }

    @Test
    fun `a run cut short names the window it was given beside the one it was asked for`() {
        val summary = asking(40.seconds).markdown()

        summary shouldContain "> **Cut short:** the schedule asked for 40.0s and the run recorded 20.0s."
    }

    @Test
    fun `a run that saw its window out says nothing extra`() {
        asking(20.seconds).markdown() shouldNotContain "Cut short"
    }

    @Test
    fun `a run that drained past its window is not a run that was cut short`() {
        asking(10.seconds).markdown() shouldNotContain "Cut short"
    }

    @Test
    fun `a result nobody recorded a timeline for has no measured window to be short of`() {
        ran(hold(200.perSecond, over = 10.seconds), Arrivals(2000L, 5.milliseconds, 0.0))
            .markdown() shouldNotContain "Cut short"
    }

    /** Twenty seconds of recorded run, against the window the plan asked for. */
    private fun asking(window: Duration): RunResult {
        val recorder = RunRecorder(startedAt)
        repeat(20) { second ->
            recorder.record(
                step = "pay",
                failure = null,
                serviceTime = 20.milliseconds,
                schedulingDelay = Duration.ZERO,
                at = second.seconds,
            )
        }
        return recorder.freeze().copy(
            plan = Plan(scenario = "checkout", steps = listOf("pay"), profile = hold(25.perSecond, over = window)),
        )
    }

    /** Two arms, where the mix that departed is not the mix that was asked for. */
    private fun mixed() = RunResult(
        startedAt = startedAt,
        steps = linkedMapOf(
            "home" to step("home", spread(150)).copy(reached = 150L),
            "search" to step("search", spread(240)).copy(reached = 120L),
            "cart" to step("cart", spread(45)).copy(reached = 45L),
            "pay" to step("pay", spread(45)).copy(reached = 45L),
        ),
        behind = timingOf(listOf(50.microseconds)),
        plan = Plan(
            arms = listOf(
                PlannedArm("browse", listOf("home", "search"), hold(40.perSecond, over = 4.seconds)),
                PlannedArm("checkout", listOf("cart", "pay"), hold(10.perSecond, over = 4.seconds)),
            ),
        ),
    )

    private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

    private val interval = Interval(28.milliseconds, 33.milliseconds)

    private fun compared() = RunResult(
        startedAt = startedAt,
        steps = mapOf("browse" to browse, "pay" to step("pay", spread(100))),
        behind = timingOf(listOf(50.microseconds)),
    )

    private val nothing = listOf(Duration.ZERO)

    @Test
    fun `the summary says what a warmed run threw away before measuring`() {
        val result = ran(hold(200.perSecond, over = 10.seconds), Arrivals(2000L, 5.milliseconds, 0.0))
            .let { it.copy(plan = it.plan.copy(warmUp = WarmUp(5.seconds))) }

        result.markdown() shouldContain "Warmed for 5.00s at 200/s, not counted."
    }

    @Test
    fun `the summary says what the run's data was drawn from, once per generator`() {
        val keys = Shape("zipf(keys=1000000, skew=1.1)", seed = 4L)
        val ids = Shape("uuids()", seed = 5L)
        val result = ran(hold(200.perSecond, over = 10.seconds), Arrivals(2000L, 5.milliseconds, 0.0))
            .let { run ->
                val arms = run.plan.arms.map { it.copy(drawn = listOf(keys, ids, keys)) }
                run.copy(plan = run.plan.copy(arms = arms))
            }

        result.markdown() shouldContain "Data: zipf(keys=1000000, skew=1.1), seed 4; uuids(), seed 5."
    }

    @Test
    fun `a summary of a run that named no generator says nothing about its data`() {
        val result = ran(hold(200.perSecond, over = 10.seconds), Arrivals(2000L, 5.milliseconds, 0.0))

        result.markdown() shouldNotContain "Data:"
    }

    @Test
    fun `a summary of a run that warmed nothing says nothing about warming`() {
        val result = ran(hold(200.perSecond, over = 10.seconds), Arrivals(2000L, 5.milliseconds, 0.0))

        result.markdown() shouldNotContain "Warmed"
    }

    @Test
    fun `the behind warning says what left and how long the schedule held`() {
        val late = timingOf(List(10) { 2.seconds })
        val result = RunResult(
            startedAt = startedAt,
            steps = mapOf("browse" to browse),
            behind = timingOf(List(10) { 900.milliseconds }),
            plan = Plan("checkout", listOf("browse"), hold(10.perSecond, over = 10.seconds)),
            timeline = List(15) { second() },
            latePerSecond = List(11) { Timing.none } + List(4) { late },
        )

        val markdown = result.markdown()

        markdown shouldContain "Asked for"
        markdown shouldContain "left over 15.0s"
        markdown shouldContain "The schedule held for 11.0s."
        markdown shouldContain "Service times below are the target at that load."
    }

    @Test
    fun `the summary says what the injector ran out of`() {
        val result = RunResult(
            startedAt = startedAt,
            steps = mapOf("browse" to browse),
            behind = timingOf(nothing),
            limits = Limits(ports = Headroom.Measured(peak = 27_998, limit = 28_232)),
        )

        result.markdown() shouldContain "The injector ran out of room:"
        result.markdown() shouldContain "ephemeral ports 27998 of 28232"
    }

    @Test
    fun `a summary of a run with room to spare says nothing about it`() {
        val result = RunResult(
            startedAt = startedAt,
            steps = mapOf("browse" to browse),
            behind = timingOf(nothing),
            limits = Limits(ports = Headroom.Measured(peak = 100, limit = 28_232)),
        )

        result.markdown() shouldNotContain "ran out of room"
    }

    @Test
    fun `the summary says whether the run's own numbers add up`() {
        val recorder = RunRecorder(startedAt)
        repeat(12 * 100) { request ->
            recorder.record("browse", null, 50.milliseconds, Duration.ZERO, at = (request / 100).seconds)
        }
        val agreeing = recorder.freeze().copy(
            plan = Plan("checkout", listOf("browse"), hold(100.perSecond, over = 12.seconds)),
            usersInFlight = List(12) { 5L },
        )

        agreeing.markdown() shouldContain "**Little's law holds:**"

        val disagreeing = agreeing.copy(usersInFlight = List(12) { 20L })
        disagreeing.markdown() shouldContain "These numbers do not add up:"
        disagreeing.markdown() shouldContain "a fault in the measurement rather than in the target"
    }

    private fun second() = Second(
        okServiceTime = timingOf(List(10) { 20.milliseconds }),
        failedServiceTime = Timing.none,
        okResponseTime = timingOf(List(10) { 40.milliseconds }),
        failedResponseTime = Timing.none,
    )

    private fun ran(profile: InjectionProfile, arrivals: Arrivals) = RunResult(
        startedAt = startedAt,
        steps = mapOf("browse" to browse),
        behind = timingOf(nothing),
        plan = Plan(scenario = "checkout", steps = listOf("browse"), profile = profile),
        arrivals = arrivals,
    )
}
