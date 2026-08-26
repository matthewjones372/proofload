package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Arrivals
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.InjectionProfile
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.hold
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.randomized
import io.github.matthewjones372.kestrel.timing
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

    private fun step(name: String, count: Long, ok: Long, failures: Map<String, Long>, response: Timing) =
        StepStats(
            name = name,
            count = count,
            ok = ok,
            failures = failures,
            // The table prints response time; service time is carried so the
            // value stays a whole StepStats, not so the report reads it.
            serviceTime = response,
            responseTime = response,
        )

    private val browse =
        step("browse", 10L, 10L, emptyMap(), timingOf(listOf(1.milliseconds, 2.milliseconds, 3.milliseconds)))

    private val payLatency = timingOf(listOf(10.milliseconds, 20.milliseconds, 30.milliseconds))

    private val startedAt = Instant.parse("2026-08-26T09:00:00Z")

    @Test
    fun `a run that fell behind says so on the first line, above the table`() {
        val result = RunResult(
            startedAt = startedAt,
            steps = mapOf(
                "browse" to browse,
                "pay" to step("pay", 100L, 97L, mapOf("status 503" to 3L), payLatency),
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
                "pay" to step("pay", 100L, 100L, emptyMap(), payLatency),
            ),
            behind = timingOf(listOf(50.microseconds)),
        )

        result.markdown() shouldBe golden("kept-up.md")
        result.markdown() shouldNotContain "Behind schedule"
    }

    @Test
    fun `a failure reason is arbitrary text, so pipes and tags cannot escape their cell`() {
        val reason = "unexpected `</td>` | status <500>"
        val hostile = step("GET /a|b", 5L, 2L, mapOf(reason to 3L), timingOf(listOf(1.milliseconds)))
        val result = RunResult(
            startedAt = startedAt,
            steps = mapOf("GET /a|b" to hostile),
            behind = timingOf(listOf(Duration.ZERO)),
        )

        result.markdown() shouldBe golden("hostile-text.md")
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

    private val nothing = listOf(Duration.ZERO)

    private fun ran(profile: InjectionProfile, arrivals: Arrivals) = RunResult(
        startedAt = startedAt,
        steps = mapOf("browse" to browse),
        behind = timingOf(nothing),
        plan = Plan(scenario = "checkout", steps = listOf("browse"), profile = profile),
        arrivals = arrivals,
    )
}
