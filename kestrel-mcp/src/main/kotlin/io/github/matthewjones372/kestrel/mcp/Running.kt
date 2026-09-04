package io.github.matthewjones372.kestrel.mcp

import io.github.matthewjones372.kestrel.Allowance
import io.github.matthewjones372.kestrel.Preview
import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.Ran
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.engine.runWithin
import io.github.matthewjones372.kestrel.export.Density
import io.github.matthewjones372.kestrel.export.json
import io.github.matthewjones372.kestrel.plan.Declaration
import io.github.matthewjones372.kestrel.plan.asSimulation
import io.github.matthewjones372.kestrel.preview
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/** Where a run got to. */
internal sealed interface Progressing {

    data class Sending(val since: Instant, val expected: Duration, val describes: String) : Progressing

    data class Finished(val result: RunResult) : Progressing

    /** Refused by the allowance, or thrown out of the engine: either way the run is over. */
    data class Stopped(val why: String) : Progressing
}

/**
 * The runs this server has started, and what became of them.
 *
 * Named `Registry` rather than `Runs`, which core already uses for a set of
 * results merged from files: two types of that name in one call chain would be
 * a confusion nobody untangles twice.
 *
 * Held in memory and lost with the process, which is the honest scope for a
 * server a client starts and stops: a run whose result outlived its server
 * would be a store to keep, migrate and clean up, and 0092 does not ask for
 * one. `report` writes the page to disk, which is where anything meant to
 * survive goes.
 */
internal class Registry(private val kestrel: () -> Kestrel = { Kestrel(progress = Progress.silent) }) {

    private val byId = ConcurrentHashMap<String, Progressing>()
    private val counter = AtomicInteger()

    /**
     * Starts one, or says why not.
     *
     * Returns as soon as the run is underway rather than when it ends. A
     * ten-minute run inside one tool call is a dead connection, a retry, and a
     * second ten-minute run against the same target.
     */
    fun start(plan: Declaration, allowance: Allowance): String {
        val simulation = plan.asSimulation()

        when (val asked = simulation.preview(allowance)) {
            is Preview.Refused -> return content("refused: ${asked.reason.described}", failed = true)

            is Preview.Allowed -> {
                // 0050 already refuses a second run inside a JVM, but it does so
                // by throwing where the caller asked to run. Refused here by
                // name instead, because a caller that gets an id back for a run
                // that never started has something to poll forever.
                sending()?.let { (id, _) ->
                    return content("$id is still sending; one run at a time", failed = true)
                }

                val id = "r-${counter.incrementAndGet()}"
                val describes = "${asked.requestsAtLeast} requests over ${asked.over} to " +
                    asked.hosts.joinToString().ifEmpty { "a host this cannot read" }
                byId[id] = Progressing.Sending(Instant.now(), asked.over, describes)

                // The last frame of a thread nobody joins. Narrowing this
                // means an exception nobody predicted leaves the run reading
                // `sending` for the life of the server, which is the one answer
                // a polling caller can never act on.
                @Suppress("TooGenericExceptionCaught")
                Thread.ofVirtual().name("kestrel-mcp-$id").start {
                    byId[id] = try {
                        when (val ran = kestrel().runWithin(allowance, simulation)) {
                            is Ran.Result -> Progressing.Finished(ran.result)
                            is Ran.Refused -> Progressing.Stopped(ran.reason.described)
                        }
                    } catch (broke: RuntimeException) {
                        // Kept rather than rethrown: nobody is on the other end
                        // of this thread, and a run that died has to be
                        // something `status` can report.
                        Progressing.Stopped(broke.message ?: broke::class.simpleName.orEmpty())
                    }
                }

                return content("""{"runId":"$id","sending":${describes.asJsonString()}}""")
            }
        }
    }

    /** What a run is doing, or what it concluded. */
    fun status(id: String?): String = when (val at = id?.let { byId[it] }) {
        null -> content("no run `$id`; start one with `run`", failed = true)

        is Progressing.Sending -> content(
            """{"state":"sending","remaining":${at.remaining().toString().asJsonString()}}""",
        )

        is Progressing.Finished -> content(at.result.json(Density.Summary))

        is Progressing.Stopped -> content(at.why, failed = true)
    }

    /** Every run this server has started, newest first. */
    fun listed(): List<Pair<String, Progressing>> = byId.entries
        .map { it.key to it.value }
        .sortedByDescending { it.first.removePrefix("r-").toIntOrNull() ?: 0 }

    fun finished(id: String): RunResult? = (byId[id] as? Progressing.Finished)?.result

    private fun sending(): Pair<String, Progressing.Sending>? = byId.entries
        .firstOrNull { it.value is Progressing.Sending }
        ?.let { it.key to it.value as Progressing.Sending }
}

/**
 * How much of the window is left, which is the countdown 0064 already computes
 * for a run watching itself.
 */
private fun Progressing.Sending.remaining(): Duration {
    val gone = Duration.parseIsoStringOrNull(java.time.Duration.between(since, Instant.now()).toString())
        ?: Duration.ZERO
    return (expected - gone).coerceAtLeast(Duration.ZERO)
}
