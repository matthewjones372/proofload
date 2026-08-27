package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Arrivals
import io.github.matthewjones372.kestrel.Change
import io.github.matthewjones372.kestrel.Comparison
import io.github.matthewjones372.kestrel.Floor
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.InjectionProfile
import io.github.matthewjones372.kestrel.Interval
import io.github.matthewjones372.kestrel.Machine
import io.github.matthewjones372.kestrel.Outcome
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.Probe
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.against
import io.github.matthewjones372.kestrel.hold
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.randomized
import io.github.matthewjones372.kestrel.timing
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
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

    private fun step(
        name: String,
        ok: List<Duration>,
        failed: List<Duration> = emptyList(),
        reasons: Map<String, Long> = emptyMap(),
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

    private val startedAt = Instant.parse("2026-08-26T09:00:00Z")

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
                    reasons = mapOf("status 503" to 3L),
                ),
            ),
            behind = timingOf(listOf(100.milliseconds)),
        )

        result.markdown() shouldBe golden("behind-schedule.md")
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

        result.markdown() shouldBe golden("kept-up.md")
        result.markdown() shouldNotContain "Behind schedule"
    }

    @Test
    fun `a failure reason is arbitrary text, so pipes and tags cannot escape their cell`() {
        val reason = "unexpected `</td>` | status <500>"
        val hostile = step(
            "GET /a|b",
            ok = List(2) { 1.milliseconds },
            failed = List(3) { 1.milliseconds },
            reasons = mapOf(reason to 3L),
        )
        val result = RunResult(
            startedAt = startedAt,
            steps = mapOf("GET /a|b" to hostile),
            behind = timingOf(listOf(Duration.ZERO)),
        )

        result.markdown() shouldBe golden("hostile-text.md")
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

        result.markdown() shouldBe golden("records-lost.md")
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

        result.markdown() shouldBe golden("hiccups.md")
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
        ) shouldBe golden("compared.md")
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

    private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

    private val interval = Interval(28.milliseconds, 33.milliseconds)

    private fun compared() = RunResult(
        startedAt = startedAt,
        steps = mapOf("browse" to browse, "pay" to step("pay", spread(100))),
        behind = timingOf(listOf(50.microseconds)),
    )

    private val nothing = listOf(Duration.ZERO)

    private fun ran(profile: InjectionProfile, arrivals: Arrivals) = RunResult(
        startedAt = startedAt,
        steps = mapOf("browse" to browse),
        behind = timingOf(nothing),
        plan = Plan(scenario = "checkout", steps = listOf("browse"), profile = profile),
        arrivals = arrivals,
    )
}
