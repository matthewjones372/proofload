package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Capacity
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.Outcome
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.Rate
import io.github.matthewjones372.kestrel.RunRecorder
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
import kotlin.time.Duration.Companion.seconds

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
            "browse" to stepOf(
                name = "browse",
                ok = listOf(10.milliseconds, 12.milliseconds, 14.milliseconds, 90.milliseconds) to
                    listOf(210.milliseconds, 412.milliseconds, 614.milliseconds, 890.milliseconds),
            ),
            "pay" to stepOf(
                name = "pay",
                ok = listOf(20.milliseconds, 40.milliseconds) to listOf(220.milliseconds, 440.milliseconds),
                failed = listOf(60.milliseconds, 800.milliseconds, 1200.milliseconds) to
                    listOf(660.milliseconds, 1600.milliseconds, 2000.milliseconds),
                reasons = linkedMapOf(HOSTILE_REASON to 2L, CLOSING_TAG_REASON to 1L),
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

    /** The same run, on an injector that stalled while it was sending. */
    val stalled: RunResult = fellBehind.copy(
        hiccups = timingOf(List(95) { 1.milliseconds } + List(4) { 14.milliseconds } + listOf(30.milliseconds)),
    )

    /** The same run, with a backlog too small to have moved anything it prints. */
    val keptSchedule: RunResult = fellBehind.copy(
        behind = timingOf(listOf(1.milliseconds, 1.milliseconds, 2.milliseconds)),
    )

    /** A hundred requests over a planned ten seconds, one of them failed and one of them slow. */
    val metItsTarget: RunResult = RunResult(
        startedAt = Instant.parse("2026-08-26T09:00:00Z"),
        steps = linkedMapOf(
            "pay" to stepOf(
                name = "pay",
                ok = List(98) { 20.milliseconds } + 800.milliseconds to
                    List(98) { 50.milliseconds } + 900.milliseconds,
                failed = listOf(20.milliseconds) to listOf(50.milliseconds),
                reasons = linkedMapOf("status 503" to 1L),
            ),
        ),
        behind = timingOf(listOf(1.milliseconds)),
        plan = Plan(
            scenario = "checkout",
            steps = listOf("pay"),
            profile = constantRate(10.perSecond, over = 10.seconds),
        ),
    )

    /** A pipeline that answered for most of what it was sent, and lost the rest. */
    val lostRecords: RunResult = RunResult(
        startedAt = Instant.parse("2026-08-26T09:00:00Z"),
        steps = linkedMapOf(
            "submitted" to stepOf(
                name = "submitted",
                ok = spread(113, 2.milliseconds, 3.milliseconds, 4.milliseconds).let { it to it },
            ),
            "settled" to stepOf(
                name = "settled",
                ok = spread(60, 400.milliseconds, 900.milliseconds, 1400.milliseconds).let { it to it },
            ).copy(unmatched = 41L, inFlight = 12L),
        ),
        behind = timingOf(listOf(1.milliseconds)),
    )

    /** Long enough that a p99.9 has a sample to rest on, with one request in a thousand slow. */
    val longEnoughForATail: RunResult = RunResult(
        startedAt = Instant.parse("2026-08-26T09:00:00Z"),
        steps = linkedMapOf(
            "pay" to stepOf(
                name = "pay",
                ok = List(1_997) { 20.milliseconds } + List(3) { 900.milliseconds } to
                    List(1_997) { 25.milliseconds } + List(3) { 950.milliseconds },
            ),
        ),
        behind = timingOf(listOf(1.milliseconds)),
    )

    /**
     * Four seconds of a target that was fine and then was not.
     *
     * Recorded through `RunRecorder` at the offsets the requests left at,
     * because a timeline built by hand would be asserting the shape of a list
     * rather than the shape of a run.
     */
    val degradedHalfway: RunResult = recorded(degrading = true)

    /** The same load at the same latency throughout, so the shape is the only difference on the page. */
    val steadyThroughout: RunResult = recorded(degrading = false)

    private fun recorded(degrading: Boolean): RunResult {
        val recorder = RunRecorder(Instant.parse("2026-08-26T09:00:00Z"))
        repeat(SECONDS_RECORDED * EACH_SECOND) { index ->
            val degraded = degrading && index >= SECONDS_RECORDED * EACH_SECOND / 2
            // One request in ten is the tail, so p50 and p99 are two lines
            // rather than one drawn twice. In the degraded half the tail is
            // also where the failures are, which is what a target under
            // pressure looks like.
            val tail = index % ONE_IN_TEN == 0
            recorder.record(
                step = "pay",
                failure = if (degraded && tail) "status 503" else null,
                serviceTime = when {
                    degraded && tail -> 900.milliseconds
                    degraded -> 400.milliseconds
                    tail -> 60.milliseconds
                    else -> 20.milliseconds
                },
                schedulingDelay = Duration.ZERO,
                at = (index * MILLIS_A_SECOND / EACH_SECOND).milliseconds,
            )
        }
        return recorder.freeze()
    }

    private const val SECONDS_RECORDED = 4
    private const val EACH_SECOND = 25
    private const val MILLIS_A_SECOND = 1_000L
    private const val ONE_IN_TEN = 10
    private const val FIFTHS = 5

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
                    ok = flatOutcome(requests - failed, took),
                    failed = flatOutcome(
                        failed,
                        took,
                        if (failed == 0L) emptyMap() else mapOf("status 503" to failed),
                    ),
                    serviceTime = flat(requests, took),
                    responseTime = flat(requests, took),
                ),
            ),
            behind = timingOf(listOf(behind)),
            plan = Plan("checkout", listOf(pay.name), constantRate(rate, over = 2.minutes), goals),
        )
        return Rung(rate, result)
    }

    private const val SECONDS_HELD = 120.0

    /** Every request at the same latency: a rung is judged on its percentiles, and a flat run has one. */
    private fun flatOutcome(samples: Long, took: Duration, reasons: Map<String, Long> = emptyMap()) =
        Outcome(flat(samples, took), flat(samples, took), reasons)

    private fun flat(samples: Long, took: Duration): Timing =
        Histogram().apply { repeat(samples.toInt()) { record(took) } }.timing()

    /**
     * A step from its two sides, each a pair of service and response samples.
     * The whole-step timings are the merge, which is what the recorder freezes.
     */
    private fun stepOf(
        name: String,
        ok: Pair<List<Duration>, List<Duration>>,
        failed: Pair<List<Duration>, List<Duration>> = emptyList<Duration>() to emptyList(),
        reasons: Map<String, Long> = emptyMap(),
    ) = StepStats(
        name = name,
        ok = Outcome(timingOf(ok.first), timingOf(ok.second)),
        failed = Outcome(timingOf(failed.first), timingOf(failed.second), reasons),
        serviceTime = timingOf(ok.first + failed.first),
        responseTime = timingOf(ok.second + failed.second),
    )

    /** Two fifths low, two fifths in the middle and the rest high: a p50 in the middle band and a p95 at the top. */
    private fun spread(samples: Int, low: Duration, middle: Duration, high: Duration): List<Duration> {
        val fifth = samples / FIFTHS
        return List(fifth * 2) { low } + List(fifth * 2) { middle } + List(samples - fifth * 4) { high }
    }

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
