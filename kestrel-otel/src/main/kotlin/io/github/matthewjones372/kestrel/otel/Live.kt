package io.github.matthewjones372.kestrel.otel

import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.Snapshot
import io.github.matthewjones372.kestrel.throttled
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableDoublePointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableGaugeData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableLongPointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableSumData
import io.opentelemetry.sdk.resources.Resource
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pushes what a run is doing to an OTLP collector at [to] no more often than
 * [every], and the run's last tick whenever it lands.
 *
 * Counts and the backlog, never a percentile: a percentile needs the histograms
 * the recorders are still writing to, and reading those while a run is timing
 * something is the lock this whole design avoids. A run's percentiles arrive
 * when the run does, through `sendOtlp`.
 *
 * [run] is the label that puts the live series and the finished export on one
 * dashboard, so a caller who wants that join passes the same string to
 * `sendOtlp`. It defaults to now, which separates two runs but joins nothing.
 *
 * The push happens on the sampler's own thread, so a collector that answers
 * slowly delays the next sample rather than a departure — which is why [every]
 * is here rather than left to the sampler's interval.
 */
public fun otlpEvery(
    every: Duration,
    to: String,
    run: String = Instant.now().toString(),
    within: Duration = 10.seconds,
): Progress = Live(run, OverHttp(to, within)).throttled(every)

/** Where a live tick goes, so what it carries can be asserted without a socket. */
internal interface Collector {

    /** Where this is pushing, for the warning that says what could not be reached. */
    val named: String

    /** Whether the collector took them. */
    fun send(metrics: List<MetricData>): Boolean

    fun close()
}

/**
 * A snapshot as OTLP, and the bookkeeping that makes its counters deltas.
 *
 * The counters carry no `step`, because a snapshot is the run rather than its
 * steps. That is also what keeps them a different series from the finished
 * export's per-step points under the same name, so a run pushed live and then
 * sent at the end is not counted twice.
 */
internal class Live(private val run: String, private val collector: Collector) : Progress {

    private val requestsPushed = AtomicLong()

    private val failuresPushed = AtomicLong()

    private val windowFrom = AtomicLong()

    private val warned = AtomicBoolean()

    private val ended = AtomicBoolean()

    override fun tick(elapsed: Duration, snapshot: Snapshot) {
        if (ended.get()) return
        val now = System.currentTimeMillis() * NANOS_PER_MILLI
        // The first window is the run's own start, which the tick's elapsed
        // names exactly: a delta whose window began when the process did would
        // claim requests that were counted before the run.
        val from = windowFrom.get().takeIf { it != 0L } ?: (now - elapsed.inWholeNanoseconds)
        windowFrom.set(now)

        val labels = Attributes.of(RUN, run)
        val metrics = listOf(
            gauge(DEPARTED, DEPARTED_HELP, labels, snapshot.departed, from, now),
            gauge(IN_FLIGHT, IN_FLIGHT_HELP, labels, snapshot.inFlight, from, now),
            counter(REQUESTS, REQUESTS_HELP, labels, requestsPushed.delta(snapshot.requests), from, now),
            counter(FAILURES, FAILURES_HELP, labels, failuresPushed.delta(snapshot.failed), from, now),
            lateness(labels, snapshot.behind, from, now),
        )

        if (!collector.send(metrics) && warned.compareAndSet(false, true)) {
            System.err.println("kestrel: ${collector.named} $WARNING")
        }
        if (snapshot.ended) {
            ended.set(true)
            collector.close()
        }
    }

    /** What has happened since the last push, given a count that only grows. */
    private fun AtomicLong.delta(total: Long): Long = total - getAndSet(total)
}

private fun gauge(
    name: String,
    help: String,
    labels: Attributes,
    value: Long,
    from: Long,
    to: Long,
): MetricData = ImmutableMetricData.createLongGauge(
    Resource.getDefault(),
    SCOPE,
    name,
    help,
    "1",
    ImmutableGaugeData.create(listOf(ImmutableLongPointData.create(from, to, labels, value))),
)

private fun counter(
    name: String,
    help: String,
    labels: Attributes,
    value: Long,
    from: Long,
    to: Long,
): MetricData = ImmutableMetricData.createLongSum(
    Resource.getDefault(),
    SCOPE,
    name,
    help,
    "1",
    ImmutableSumData.create(
        true,
        AggregationTemporality.DELTA,
        listOf(ImmutableLongPointData.create(from, to, labels, value)),
    ),
)

/**
 * The newest lateness, under a name of its own.
 *
 * `kestrel.behind` is the finished run's distribution over every departure, and
 * one name cannot be a histogram and a gauge at once without a collector
 * dropping whichever it saw second. This is the last sample the scheduler took,
 * which is what a watcher at minute four is asking for.
 */
private fun lateness(labels: Attributes, behind: Duration, from: Long, to: Long): MetricData =
    ImmutableMetricData.createDoubleGauge(
        Resource.getDefault(),
        SCOPE,
        BEHIND_LAST,
        BEHIND_LAST_HELP,
        "s",
        ImmutableGaugeData.create(
            listOf(
                ImmutableDoublePointData.create(
                    from,
                    to,
                    labels,
                    behind.inWholeNanoseconds / NANOS_PER_SECOND.toDouble(),
                ),
            ),
        ),
    )

/**
 * The exporter, built once and let go when the run ends.
 *
 * A refusal is a warning rather than an exception for the reason `sendOtlp`
 * answers with a value: a two-hour test that dies at minute one because an
 * observability backend was down has lost the measurement, which is the thing
 * that mattered.
 */
private class OverHttp(private val endpoint: String, private val within: Duration) : Collector {

    override val named: String get() = endpoint

    private val exporter by lazy {
        OtlpHttpMetricExporter.builder()
            .setEndpoint(endpoint)
            .setTimeout(within.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .build()
    }

    override fun send(metrics: List<MetricData>): Boolean {
        val result = exporter.export(metrics)
        result.join(within.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        return result.isSuccess
    }

    override fun close() {
        exporter.shutdown().join(within.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }
}

private val SCOPE: InstrumentationScopeInfo = InstrumentationScopeInfo.create("kestrel")

private val RUN: AttributeKey<String> = AttributeKey.stringKey("run")

private const val DEPARTED = "kestrel.departed"
private const val IN_FLIGHT = "kestrel.in_flight"
private const val REQUESTS = "kestrel.requests"
private const val FAILURES = "kestrel.failures"
private const val BEHIND_LAST = "kestrel.behind.last"

private const val DEPARTED_HELP = "Users the scheduler has handed to a thread so far."

private const val IN_FLIGHT_HELP =
    "Users still running. A scenario that pauses counts a parked user here, so this is users in flight " +
        "rather than requests in flight."

private const val REQUESTS_HELP =
    "Requests recorded since the last push, every step counted together. Read approximately: each shard " +
        "publishes its own count without stopping anything, so a push can land between two of them."

private const val FAILURES_HELP =
    "Failures recorded since the last push. The reason each gave arrives with the finished run, which is " +
        "where the failures are counted by reason."

private const val BEHIND_LAST_HELP =
    "How late the last departure was against the schedule the profile promised. Read it before any count " +
        "here: where this is growing the load named is no longer being offered."

private const val WARNING =
    "refused a live push and this run is not stopping for it; the exporter logged why, and the " +
        "measurement arrives with the finished run"

private const val NANOS_PER_MILLI = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000L
