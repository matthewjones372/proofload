package io.github.matthewjones372.kestrel

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How far a run has got, as the scheduler sees it: [departed] users sent,
 * [inFlight] of them still running, and the last departure [behind] the offset
 * its profile named. [ended] marks the run's last tick.
 *
 * Nothing here comes from what was recorded. A live histogram read for a
 * percentile, or shards merged to count requests, would put the watcher's work
 * on the path being timed, and a tool that moves what it measures reports its
 * own weight as the target's latency. These are numbers the scheduler already
 * keeps, so a run costs the same watched as unwatched.
 */
data class Snapshot(
    val departed: Long,
    val inFlight: Long,
    val behind: Duration,
    val ended: Boolean,

    /**
     * The window the profile asked for, which the scheduler has held since
     * before the first departure.
     *
     * Here rather than measured, because it is not a measurement: it is what
     * was asked for, and the only honest thing to say about when a run will
     * stop sending. When the last response lands is the target's business and
     * nothing here knows it.
     */
    val scheduled: Duration = Duration.ZERO,
)

/**
 * Where a run says what it is doing while it is still doing it, so a ten-minute
 * soak is not ten minutes of silence somebody kills.
 */
fun interface Progress {

    fun tick(elapsed: Duration, snapshot: Snapshot)

    /**
     * What is about to run, before the first departure.
     *
     * Every figure here is read off the plan rather than measured, so it is
     * available before anything is sent — which is the point: a reader deciding
     * whether to wait cannot wait for a measurement to tell them.
     */
    fun starting(plan: Plan) {}

    /**
     * A capacity search is about to climb, and the most its holds can add up
     * to. An upper bound rather than a forecast: the ladder stops as soon as it
     * has the knee, so this is nearly always beaten.
     */
    fun searching(search: Search) {}

    /**
     * A rung finished: what it was asked to hold, what became of it, and the
     * most that can still be held after it.
     */
    fun climbed(rung: Rung, number: Int, atMost: Duration) {}

    /**
     * How long this run queued for the machine before it could start.
     *
     * Only a run that waited is told about, so a reporter hearing this knows
     * the number it is about to print describes a machine that was busy. Where
     * nothing queued, nothing is said.
     */
    fun waited(queued: Duration) {}

    companion object {

        /** For a caller whose output is somebody else's report — a test framework, a CI step that parses stdout. */
        val silent: Progress = Progress { _, _ -> }

        /**
         * A line on stdout no more often than [every], and one when the run ends.
         *
         * The engine samples faster than a line is due, so [every] is this
         * reporter's own throttle rather than the rate it is asked at; the
         * run's last tick prints whenever it lands, so a run shorter than one
         * interval still says what it did.
         */
        fun lines(every: Duration = 5.seconds): Progress = Lines(every)
    }
}

/**
 * The ticks a run's sampler sends and the one its own thread sends at the end
 * never overlap — the sampler is stopped first — so this holds the next due
 * time rather than guarding it.
 */
private class Lines(private val every: Duration) : Progress {

    private val due = AtomicLong(every.inWholeNanoseconds)

    override fun tick(elapsed: Duration, snapshot: Snapshot) {
        if (!snapshot.ended && elapsed.inWholeNanoseconds < due.get()) return
        due.set(elapsed.inWholeNanoseconds + every.inWholeNanoseconds)
        println(snapshot.line(elapsed))
    }

    override fun starting(plan: Plan) {
        if (plan.profile == null) return
        // The warm-up is named where the run is announced rather than ticked
        // through: a reader watching a run start otherwise sees nothing for as
        // long as it warms and assumes the tool has hung.
        val warming = plan.warmUp?.let { " (after warming for ${it.over})" }.orEmpty()
        println(
            "kestrel: ${plan.scenario} — ${plan.plannedUsers.grouped()} users over ${plan.plannedWindow}, " +
                "${plan.steps.size} ${if (plan.steps.size == 1) "step" else "steps"} each$warming",
        )
    }

    override fun searching(search: Search) {
        println(
            "kestrel: capacity — at most ${search.rungs.size} rungs of ${search.holding} and the bisection " +
                "after them, so at most ${search.worstCase}",
        )
    }

    override fun waited(queued: Duration) {
        println("kestrel: waited $queued for the machine")
    }

    override fun climbed(rung: Rung, number: Int, atMost: Duration) {
        val became = when (rung.outcome) {
            Rung.Outcome.Passed -> "passed"

            Rung.Outcome.Failed -> "failed"

            // The ladder stops here, so there is no bound left to narrow. What
            // a reader needs is that the answer is about this machine, and
            // what the rung did measure: the load that left, and the target's
            // service time at it.
            Rung.Outcome.Void ->
                "void — the generator lost ground, so this is about the machine: " +
                    "${rung.offered.forReading()}/s left, service p99 ${rung.serviceAtThatLoad()}"
        }
        val left = if (rung.outcome == Rung.Outcome.Void) "" else ", at most $atMost left"
        println("kestrel: rung $number — ${rung.rate.forReading()}/s $became$left")
    }
}

// The same `kestrel:` prefix the report writer uses, so a run reads as one
// program saying several things rather than as two programs talking over
// each other.
private fun Snapshot.line(elapsed: Duration): String = listOfNotNull(
    "kestrel: ${elapsed.clock()}",
    "departed ${departed.grouped()}",
    "in flight ${inFlight.grouped()}",
    "behind $behind",
    left(elapsed),
).joinToString(separator = "  ")

/**
 * What the schedule has left, or that it has none.
 *
 * `draining` rather than a zero or a negative countdown: past the window the
 * run is waiting out the users it started, and how long that takes belongs to
 * the target. A number here would claim this end of the wire knew.
 */
private fun Snapshot.left(elapsed: Duration): String? = when {
    scheduled <= Duration.ZERO -> null

    elapsed >= scheduled -> "draining"

    // Rounded up, so a run with eight hundred milliseconds to go does not
    // report none: this is a bound like every other figure here, and one that
    // reads 00:00 while the schedule is still asking for departures is under it.
    else -> "${(scheduled - elapsed).upToTheSecond().clock()} left"
}

private fun Duration.upToTheSecond(): Duration =
    if (inWholeNanoseconds % NANOS_PER_SECOND == 0L) this else (inWholeSeconds + 1).seconds

// Built rather than formatted, so the line reads the same under every locale. A
// soak past an hour counts on in minutes instead of growing a third field.
private fun Duration.clock(): String = "${inWholeMinutes.padded()}:${(inWholeSeconds % SECONDS_PER_MINUTE).padded()}"

private fun Long.padded(): String = toString().padStart(2, '0')

/** A whole rate reads as one; a bisected one keeps the digit that makes it different from its neighbours. */

/** What a void rung still measured: the target's service time at the load that left. */
private fun Rung.serviceAtThatLoad(): Duration =
    result.steps.values.maxOfOrNull { it.serviceTime.p99 } ?: Duration.ZERO

private fun Rate.forReading(): String =
    if (perSecond == floor(perSecond)) perSecond.toLong().grouped() else String.format(Locale.ROOT, "%.1f", perSecond)

private fun Long.grouped(): String = toString().reversed().chunked(THOUSAND).joinToString(",").reversed()

private const val SECONDS_PER_MINUTE = 60

private const val THOUSAND = 3

private const val NANOS_PER_SECOND = 1_000_000_000L
