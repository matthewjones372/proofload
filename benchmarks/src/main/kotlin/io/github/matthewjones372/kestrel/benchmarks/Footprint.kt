package io.github.matthewjones372.kestrel.benchmarks

import com.sun.management.ThreadMXBean
import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.run
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a run holds, and what it allocates to hold it.
 *
 * 0011 measured whether the generator keeps its schedule and handed the JVM
 * `-Xmx2g` so it would not have to answer this. Three decisions already argue
 * from a footprint nobody had taken — the booking window, histograms over
 * sample lists, and the refusal of a metric per request — and allocation on the
 * timed path is a collection pause this tool would record as the target's
 * latency.
 *
 * Two numbers, taken differently because they answer different questions.
 * **Retained** is the live set after the run and a full collection, minus the
 * same reading before it: what a container has to hold. **Allocated per
 * departure** is what the run churned, which is the one that can be wrong in a
 * way that changes a reported latency.
 */
fun main() {
    val rungs = USERS.map(::measure)

    val page = report(rungs)
    println(page)

    val into = Path.of("build/reports/kestrel/footprint.md")
    Files.createDirectories(into.parent)
    Files.writeString(into, page)
    println("written to ${into.toAbsolutePath()}")
}

private fun measure(users: Int): Footprint {
    val rate = users / WINDOW.inWholeSeconds.toInt()
    // A step that touches nothing, so the row is what the tool holds rather
    // than what a client's buffers do.
    val nothing = scenario("nothing") { exec("step") { } }

    // Thrown away: the first run pays for class loading and JIT, and charging
    // that to the smallest rung would report a footprint that moves with the
    // order the rungs happen to be in.
    nothing.at(rate.perSecond, over = WARMUP).run(Progress.silent)

    val before = settled()
    val allocatedBefore = allocated()
    val result = nothing.at(rate.perSecond, over = WINDOW).run(Progress.silent)
    val churned = allocated() - allocatedBefore
    val peak = usedNow()
    val after = settled()

    return Footprint(users, result, retained = after - before, peak = peak, allocated = churned)
}

internal class Footprint(
    val users: Int,
    val result: RunResult,
    /** Live bytes the run left behind, after a full collection. */
    val retained: Long,
    /** The most this process held while it ran, which is what an operator provisions. */
    val peak: Long,
    /** Bytes the injector's own threads allocated across the window. */
    val allocated: Long,
) {

    val samples: Long get() = result.count

    val perUser: Long get() = if (users == 0) 0 else retained / users

    val perSample: Long get() = if (samples == 0L) 0 else retained / samples

    val perDeparture: Long get() = if (samples == 0L) 0 else allocated / samples

    /**
     * Whether this row measured a design or a backlog.
     *
     * A run that lost its schedule is holding departures it has not sent, so
     * its footprint is the queue rather than the shape of the thing.
     *
     * Judged on the departure budget rather than on `fellBehind`, for the
     * reason the ceiling harness already gives: `fellBehind` weighs the backlog
     * against the response time it inflates, and a step that touches nothing
     * has no response time to weigh it against, so it answers yes at every
     * rate.
     */
    val kept: Boolean get() = result.behind.p50 <= BUDGET
}

/**
 * The live set, after asking for a collection and letting it happen.
 *
 * `System.gc` is a request, so this reads the collector's own count and waits
 * for it to move rather than trusting a sleep. Not exact, and it does not have
 * to be: the question is whether a run holds kilobytes or megabytes per user.
 */
private fun settled(): Long {
    val collector = ManagementFactory.getGarbageCollectorMXBeans()
    val before = collector.sumOf { it.collectionCount }

    repeat(COLLECTIONS) { System.gc() }
    val deadline = System.nanoTime() + SETTLING.inWholeNanoseconds
    while (collector.sumOf { it.collectionCount } == before && System.nanoTime() < deadline) {
        Thread.onSpinWait()
    }

    return usedNow()
}

private fun usedNow(): Long = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used

/**
 * What every thread in this process has allocated, summed.
 *
 * Per-thread rather than off the heap: a run's allocation is spread across the
 * virtual threads that carry the users and the platform threads that schedule
 * them, and the heap's own counter cannot separate what was allocated from what
 * was collected while it ran.
 */
private fun allocated(): Long {
    val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
    if (!threads.isThreadAllocatedMemorySupported) return 0
    return threads.allThreadIds.sumOf { maxOf(0L, threads.getThreadAllocatedBytes(it)) }
}

private fun report(rungs: List<Footprint>): String = buildString {
    appendLine("# What a run holds")
    appendLine()
    appendLine("Retained is the live set after a full collection, minus the same reading before the run.")
    appendLine("Allocated per departure is what the injector's threads churned, which is the number")
    appendLine("that becomes a collection pause this tool would otherwise report as the target's latency.")
    appendLine()
    appendLine("| users | retained | per user | per sample | allocated/departure | peak heap | kept schedule |")
    appendLine("|---|---|---|---|---|---|---|")
    rungs.forEach { rung ->
        appendLine(
            "| ${rung.users.grouped()} | ${rung.retained.asBytes()} | ${rung.perUser.asBytes()} | " +
                "${rung.perSample.asBytes()} | ${rung.perDeparture.asBytes()} | ${rung.peak.asBytes()} | " +
                "${if (rung.kept) "yes" else "**no**"} |",
        )
    }
    appendLine()
    appendLine("Measured on ${rungs.firstOrNull()?.result?.machine}.")
    if (rungs.any { !it.kept }) {
        appendLine()
        appendLine("A row that did not keep its schedule is holding departures it has not sent:")
        appendLine("that footprint is the backlog, not the design.")
    }
}

private fun Long.asBytes(): String = when {
    this >= MEGABYTE -> String.format(Locale.ROOT, "%.1f MB", toDouble() / MEGABYTE)
    this >= KILOBYTE -> String.format(Locale.ROOT, "%.1f KB", toDouble() / KILOBYTE)
    else -> "$this B"
}

private fun Int.grouped(): String = String.format(Locale.ROOT, "%,d", this)

/** Three rungs an order of magnitude apart, all of which a working run reaches. */
private val USERS = listOf(1_000, 10_000, 50_000)
private val WINDOW = 10.seconds
private val WARMUP = 2.seconds
private val SETTLING = 2.seconds

/** The same budget the ceiling harness judges a departure by. */
private val BUDGET = 1.milliseconds
private const val COLLECTIONS = 3
private const val KILOBYTE = 1_024
private const val MEGABYTE = 1_024 * 1_024
