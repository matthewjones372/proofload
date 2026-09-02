package io.github.matthewjones372.kestrel.otel

import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Timing
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableHistogramData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableHistogramPointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableLongPointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableSumData
import io.opentelemetry.sdk.resources.Resource
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether a collector took a run's measurements.
 *
 * A value rather than an exception, for the reason a report is written rather
 * than thrown: a load test that dies because an observability backend was down
 * is one people stop running, and the measurement it was carrying is the thing
 * that mattered.
 */
public sealed interface Sent {

    /** The collector answered, and took them. */
    public data object Accepted : Sent

    /** It did not, and this is what to go and look at. */
    public data class Refused(val why: String) : Sent
}

/**
 * Sends what this run measured to an OTLP collector at [endpoint], as one
 * delta export.
 *
 * Delta rather than cumulative: cumulative claims a counter that has been
 * running for the life of a process, with the restart detection that implies,
 * and a run is one window with a start and an end that the result already
 * knows.
 *
 * Explicit buckets, never an exponential histogram. This histogram's
 * boundaries are log-linear and an exponential one's are geometric, so the
 * counts would have to be moved across boundaries that were measured — which
 * is the interpolation everything here refuses, done at export time where
 * nobody would see it.
 *
 * [run] tells ten runs on one dashboard apart and defaults to when this one
 * started, which is the only thing a result carries that separates it from
 * another.
 */
public fun RunResult.sendOtlp(
    endpoint: String,
    run: String = startedAt.toString(),
    within: Duration = 10.seconds,
): Sent {
    val exporter = OtlpHttpMetricExporter.builder()
        .setEndpoint(endpoint)
        .setTimeout(within.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .build()

    return try {
        val result = exporter.export(metricData(run))
        result.join(within.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (result.isSuccess) {
            Sent.Accepted
        } else {
            Sent.Refused("$endpoint did not take this run's measurements; the exporter logged why")
        }
    } finally {
        exporter.shutdown().join(within.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }
}

/**
 * The same measurements as a collection of OTLP metrics, for a caller wiring
 * their own exporter or reader.
 *
 * The measurements only: the plan, the goals and their verdicts, the intervals
 * and every "cannot tell" stay in the report. A series meaning "this might be
 * noise" is a series that gets alerted on as though it were not.
 */
public fun RunResult.metricData(run: String = startedAt.toString()): List<MetricData> {
    val window = startedAt.toEpochMilli() * NANOS_PER_MILLI
    val ends = window + timeline.size * NANOS_PER_SECOND
    val runLabel = Attributes.of(RUN, run)

    return steps.values.sortedBy { it.name }
        .flatMap { step -> step.sides(run) }
        .map { (labels, timing) -> histogram(LATENCY, LATENCY_HELP, labels, timing, window, ends) }
        .plus(
            listOfNotNull(
                behind.takeIf { it.count > 0L }
                    ?.let { histogram(BEHIND, BEHIND_HELP, runLabel, it, window, ends) },
                hiccups.takeIf { it.count > 0L }
                    ?.let { histogram(HICCUPS, HICCUPS_HELP, runLabel, it, window, ends) },
            ),
        )
        .plus(counters(run, window, ends))
}

private fun StepStats.sides(run: String): List<Pair<Attributes, Timing>> = listOf(
    attributes(run, name, "ok", "service") to ok.serviceTime,
    attributes(run, name, "ok", "response") to ok.responseTime,
    attributes(run, name, "failed", "service") to failed.serviceTime,
    attributes(run, name, "failed", "response") to failed.responseTime,
).filter { (_, timing) -> timing.count > 0L }

private fun attributes(run: String, step: String, outcome: String, clock: String): Attributes =
    Attributes.builder().put(RUN, run).put(STEP, step).put(OUTCOME, outcome).put(CLOCK, clock).build()

/**
 * One timing as an explicit-bucket histogram point.
 *
 * The boundaries are this histogram's own bucket tops and the counts are its
 * own counts, so nothing is re-bucketed. OTel's model insists on a sum, which
 * nothing here measures — [sumAtBucketTops] is what it would have been had
 * every sample sat at the top of the bucket it was counted in, which is an
 * upper bound and is what the description says it is.
 */
private fun histogram(
    name: String,
    help: String,
    labels: Attributes,
    timing: Timing,
    from: Long,
    to: Long,
): MetricData = ImmutableMetricData.createDoubleHistogram(
    Resource.getDefault(),
    SCOPE,
    name,
    help,
    "s",
    ImmutableHistogramData.create(
        AggregationTemporality.DELTA,
        listOf(
            ImmutableHistogramPointData.create(
                from,
                to,
                labels,
                timing.sumAtBucketTops(),
                false,
                0.0,
                true,
                timing.max.asSeconds(),
                timing.boundaries(),
                timing.counts(),
            ),
        ),
    ),
)

/** Boundaries are the bucket tops but the last: OTel's final bucket is everything above. */
private fun Timing.boundaries(): List<Double> = distribution.dropLast(1).map { it.upperBound.asSeconds() }

private fun Timing.counts(): List<Long> = distribution.map { it.count }

private fun Timing.sumAtBucketTops(): Double =
    distribution.sumOf { it.upperBound.asSeconds() * it.count }

private fun RunResult.counters(run: String, from: Long, to: Long): List<MetricData> = listOfNotNull(
    steps.values.takeIf { it.isNotEmpty() }?.let { every ->
        ImmutableMetricData.createLongSum(
            Resource.getDefault(),
            SCOPE,
            REQUESTS,
            REQUESTS_HELP,
            "1",
            ImmutableSumData.create(
                true,
                AggregationTemporality.DELTA,
                every.sortedBy { it.name }.map { step ->
                    ImmutableLongPointData.create(
                        from,
                        to,
                        Attributes.builder().put(RUN, run).put(STEP, step.name).build(),
                        step.count,
                    )
                },
            ),
        )
    },
    steps.values.flatMap { step ->
        step.failed.reasons.entries.sortedBy { it.key.described }.map { (reason, count) ->
            ImmutableLongPointData.create(
                from,
                to,
                Attributes.builder().put(RUN, run).put(STEP, step.name).put(REASON, reason.described).build(),
                count,
            )
        }
    }.takeIf { it.isNotEmpty() }?.let { points ->
        ImmutableMetricData.createLongSum(
            Resource.getDefault(),
            SCOPE,
            FAILURES,
            FAILURES_HELP,
            "1",
            ImmutableSumData.create(true, AggregationTemporality.DELTA, points),
        )
    },
)

private fun kotlin.time.Duration.asSeconds(): Double = inWholeNanoseconds / NANOS_PER_SECOND.toDouble()

private val SCOPE: InstrumentationScopeInfo = InstrumentationScopeInfo.create("kestrel")

private val RUN: AttributeKey<String> = AttributeKey.stringKey("run")
private val STEP: AttributeKey<String> = AttributeKey.stringKey("step")
private val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("outcome")
private val CLOCK: AttributeKey<String> = AttributeKey.stringKey("clock")
private val REASON: AttributeKey<String> = AttributeKey.stringKey("reason")

private const val LATENCY = "kestrel.latency"
private const val BEHIND = "kestrel.behind"
private const val HICCUPS = "kestrel.hiccups"
private const val REQUESTS = "kestrel.requests"
private const val FAILURES = "kestrel.failures"

private val LATENCY_HELP =
    "What the target took, and what it took counted from the departure the profile promised. Every " +
        "sample is counted at the top of the bucket it fell in, so a quantile off these is good to " +
        "${Histogram.PRECISION * HUNDRED}% and no better, and the sum is what it would have been had " +
        "every sample sat at its bucket top rather than a sum anything measured."

private const val BEHIND_HELP =
    "How late this run's departures were against the schedule the profile promised. Read it before any " +
        "latency here: where this is large the load named was never offered."

private const val HICCUPS_HELP =
    "What the injector's own JVM stalled for while it measured, off the timed path. A tail smaller than " +
        "this is the measuring process rather than the target."

private const val REQUESTS_HELP = "Requests recorded under each step, successful and failed together."

private const val FAILURES_HELP = "Failures by the reason the module that made the request gave."

private const val NANOS_PER_MILLI = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val HUNDRED = 100.0
