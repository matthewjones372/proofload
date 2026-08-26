package io.github.matthewjones372.kestrel

import java.time.Instant
import kotlin.time.Duration

/** A histogram read once and frozen: percentiles that cannot move under a reader. */
data class Timing(
    val count: Long,
    val p50: Duration,
    val p95: Duration,
    val p99: Duration,
    val max: Duration,
)

fun Histogram.timing(): Timing = Timing(
    count = count,
    p50 = percentile(P50),
    p95 = percentile(P95),
    p99 = percentile(P99),
    max = max,
)

/**
 * What one step did. `serviceTime` is what the target took; `responseTime` is
 * measured from the departure the profile promised, so a generator that fell
 * behind reports it here rather than as the target being fast.
 */
data class StepStats(
    val name: String,
    val count: Long,
    val ok: Long,
    val failures: Map<String, Long>,
    val serviceTime: Timing,
    val responseTime: Timing,
) {
    val failed: Long get() = count - ok
}

/** What a run measured, as a value: assert on it, diff it, hand it to a report. */
data class RunResult(
    val startedAt: Instant,
    val steps: Map<String, StepStats>,
    val behind: Timing,
) {
    val count: Long get() = steps.values.sumOf { it.count }

    val ok: Long get() = steps.values.sumOf { it.ok }

    val failed: Long get() = count - ok

    operator fun get(step: String): StepStats = requireNotNull(steps[step]) {
        "no step named '$step' ran; this simulation had ${steps.keys.sorted()}"
    }
}

private const val P50 = 50.0
private const val P95 = 95.0
private const val P99 = 99.0
