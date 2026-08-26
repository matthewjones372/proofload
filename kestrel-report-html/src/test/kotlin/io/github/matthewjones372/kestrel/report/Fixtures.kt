package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Capacity
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.Rate
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Rung
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.constantRate
import io.github.matthewjones372.kestrel.failureRate
import io.github.matthewjones372.kestrel.p99
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.percent
import io.github.matthewjones372.kestrel.step
import io.github.matthewjones372.kestrel.timing
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Results built by hand, sample by sample. No engine runs here, so the
 * percentiles in the goldens are the histogram's own arithmetic over a list
 * anyone can read, and a golden that moves has one of two causes.
 */
internal object Fixtures {

    /** A reason a target could really produce, chosen to break naive escaping. */
    const val HOSTILE_REASON: String = """status 503 <b>"upstream" & 'down'</b>"""

    /** The other way an inlined payload escapes its element. */
    const val CLOSING_TAG_REASON: String = "</script> hung up"

    val fellBehind: RunResult = RunResult(
        startedAt = Instant.parse("2026-08-26T09:00:00Z"),
        steps = linkedMapOf(
            "browse" to StepStats(
                name = "browse",
                count = 4L,
                ok = 4L,
                failures = emptyMap(),
                serviceTime = timingOf(listOf(10.milliseconds, 12.milliseconds, 14.milliseconds, 90.milliseconds)),
                responseTime = timingOf(listOf(210.milliseconds, 412.milliseconds, 614.milliseconds, 890.milliseconds)),
            ),
            "pay" to StepStats(
                name = "pay",
                count = 5L,
                ok = 2L,
                failures = linkedMapOf(HOSTILE_REASON to 2L, CLOSING_TAG_REASON to 1L),
                serviceTime = timingOf(
                    listOf(20.milliseconds, 40.milliseconds, 60.milliseconds, 800.milliseconds, 1200.milliseconds),
                ),
                responseTime = timingOf(
                    listOf(220.milliseconds, 440.milliseconds, 660.milliseconds, 1600.milliseconds, 2000.milliseconds),
                ),
            ),
        ),
        behind = timingOf(
            listOf(
                200.milliseconds, 400.milliseconds, 600.milliseconds, 800.milliseconds,
                200.milliseconds, 400.milliseconds, 600.milliseconds, 800.milliseconds,
                800.milliseconds,
            ),
        ),
    )

    /** The same run, with a backlog too small to have moved anything it prints. */
    val keptSchedule: RunResult = fellBehind.copy(
        behind = timingOf(listOf(1.milliseconds, 1.milliseconds, 2.milliseconds)),
    )

    /** Long enough that a p99.9 has a sample to rest on, with one request in a thousand slow. */
    val longEnoughForATail: RunResult = RunResult(
        startedAt = Instant.parse("2026-08-26T09:00:00Z"),
        steps = linkedMapOf(
            "pay" to StepStats(
                name = "pay",
                count = 2_000L,
                ok = 2_000L,
                failures = emptyMap(),
                serviceTime = timingOf(List(1_997) { 20.milliseconds } + List(3) { 900.milliseconds }),
                responseTime = timingOf(List(1_997) { 25.milliseconds } + List(3) { 950.milliseconds }),
            ),
        ),
        behind = timingOf(listOf(1.milliseconds)),
    )

    private val pay = step("pay")

    private val goals = listOf(p99(pay) under 200.milliseconds, failureRate under 1.percent)

    /** A ladder to 4,000/s, a knee between 3,000 and 4,000, and a bisection that found 3,500. */
    val capacity: Capacity = Capacity(
        listOf(
            rung(1_000.perSecond, took = 40.milliseconds, failed = 0L),
            rung(2_000.perSecond, took = 60.milliseconds, failed = 0L),
            rung(3_000.perSecond, took = 110.milliseconds, failed = 0L),
            rung(3_500.perSecond, took = 180.milliseconds, failed = 0L),
            rung(4_000.perSecond, took = 900.milliseconds, failed = 240L),
            rung(5_000.perSecond, took = 1200.milliseconds, failed = 9_000L),
        ),
    )

    /** The same ladder, stopped by a rung the injector could not offer. */
    val voidedCapacity: Capacity = Capacity(
        capacity.curve.take(3) + rung(4_000.perSecond, took = 90.milliseconds, failed = 0L, behind = 80.milliseconds),
    )

    private fun rung(rate: Rate, took: Duration, failed: Long, behind: Duration = 100.microseconds): Rung {
        val requests = (rate.perSecond * SECONDS_HELD).toLong()
        val result = RunResult(
            startedAt = Instant.parse("2026-08-26T09:00:00Z"),
            steps = mapOf(
                pay.name to StepStats(
                    name = pay.name,
                    count = requests,
                    ok = requests - failed,
                    failures = if (failed == 0L) emptyMap() else mapOf("status 503" to failed),
                    serviceTime = timingOf(listOf(took)),
                    responseTime = timingOf(listOf(took)),
                ),
            ),
            behind = timingOf(listOf(behind)),
            plan = Plan("checkout", listOf(pay.name), constantRate(rate, over = 2.minutes), goals),
        )
        return Rung(rate, result)
    }

    private const val SECONDS_HELD = 120.0

    // `vararg Duration` is prohibited: `Duration` is a value class.
    private fun timingOf(samples: List<Duration>): Timing =
        Histogram().apply { samples.forEach { record(it) } }.timing()
}

/** A checked-in expected output, read as bytes rather than rebuilt in the test. */
internal object Golden {

    fun text(name: String): String {
        val stream = requireNotNull(javaClass.getResourceAsStream("/golden/$name")) {
            "no golden file at kestrel-report-html/src/test/resources/golden/$name"
        }
        return stream.reader(Charsets.UTF_8).use { it.readText() }
    }
}
