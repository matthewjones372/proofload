package io.github.matthewjones372.proofload.benchmarks

import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.perSecond
import java.nio.file.Files
import java.nio.file.Path

/**
 * The over-a-socket sweep with the target out of this JVM.
 *
 * `docs/what-it-costs.md` publishes a lower bound and says three times over
 * that it cannot say whose limit it is: the target shares this process's heap,
 * its JIT and its cores. This removes the first two. The cores are still
 * shared, so this is not yet the client's number either — it is one confound
 * fewer, and the page says which.
 *
 * A task of its own rather than a third table in `:benchmarks:ceiling`, because
 * 0057's rule is that the published ceiling must not move, and a sweep that
 * starts a process per rate takes long enough to be worth asking for on purpose.
 */
fun main() {
    // Before anything is measured: this pins the generator and leaves the
    // target's processors where `apart` will find them.
    val pinned = Pinning.apply()
    val measured = SOCKET_RATES.map(::measureApart)

    val page = apartReport(measured, pinned)
    println(page)

    val into = Path.of("build/reports/proofload/ceiling-apart.md")
    Files.createDirectories(into.parent)
    Files.writeString(into, page)
    println("written to ${into.toAbsolutePath()}")
}

private fun measureApart(rate: Int): Measured {
    // A target of its own for the warm-up, as the in-process sweep does: a row
    // reports what this rate's target took, and a shared one would answer for
    // two windows.
    apart { warming -> hitting(warming.baseUrl).at(rate.perSecond, over = SWEEP_WARMUP).run(Progress.silent) }

    val run = apart { target ->
        hitting(target.baseUrl).at(rate.perSecond, over = SWEEP_WINDOW).run(Progress.silent)
    }
    return Measured(rate, run.answered, loadAverage(), served = run.served, connections = run.connections)
}

internal fun apartReport(measured: List<Measured>, pinned: Pinning.Pinned): String {
    val ceiling = measured.lastOrNull { it.keptSchedule && it.answeredEverything }
    val loads = measured.map { it.load }.filter { it >= 0.0 }
    return (
        listOf(
            "# What this tool costs, with the target out of the way",
            "",
            "The same sweep as `:benchmarks:ceiling`'s over-a-socket table and the same rule —",
            "a rate kept its schedule when the median departure left within $SWEEP_BUDGET of when it",
            "was due — against a target in a JVM of its own rather than in this one.",
            "",
            "**Still a lower bound.** The target no longer shares this process's heap, its",
            "garbage collector or its JIT, and it is no longer `com.sun.net.httpserver` running",
            "on the generator's own safepoints. What it shares now is stated rather than assumed:",
            "this sweep ran ${pinned.described}.",
            "",
            "**Per conn** is requests over the distinct client ports the target answered on,",
            "counted at the far end of this run's own sockets. It is the column that says whether",
            "a rate ran out of connections rather than out of anything else, and it is not the",
            "machine-wide port reading beside it, which carries whatever the last sweep left.",
            "",
            "| Rate | Requests | Failed | Failed as | Behind p50 | Behind p99 | Behind max | " +
                "Served p50 | Served p99 | Per conn | Files | Ports | p50 within $SWEEP_BUDGET | fellBehind() |",
            "|---:|---:|---:|:---|---:|---:|---:|---:|---:|---:|---:|---:|:---:|:---:|",
        ) + measured.map { it.socketRow() } + listOf(
            "",
            ceiling
                ?.let { "Ceiling with the target apart: **${it.rate.grouped()} a second**, a lower bound." }
                ?: "No rate here kept its schedule with nothing failed.",
            "",
            "Measured on ${machine()}, under " +
                if (loads.isEmpty()) {
                    "a load average this JVM could not read."
                } else {
                    "a one-minute load average of ${loads.min().rounded()} " +
                        "to ${loads.max().rounded()} across the sweep."
                },
        )
        )
        .joinToString(separator = "\n", postfix = "\n")
}
