package io.github.matthewjones372.kestrel.mcp

import io.github.matthewjones372.kestrel.Allowance
import io.github.matthewjones372.kestrel.Preview
import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.Ran
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.baseline.readBaseline
import io.github.matthewjones372.kestrel.baseline.writeBaseline
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.engine.runWithin
import io.github.matthewjones372.kestrel.export.Density
import io.github.matthewjones372.kestrel.export.json
import io.github.matthewjones372.kestrel.plan.Declaration
import io.github.matthewjones372.kestrel.plan.asSimulation
import io.github.matthewjones372.kestrel.plan.asYaml
import io.github.matthewjones372.kestrel.plan.kafka.kafkaLowerings
import io.github.matthewjones372.kestrel.plan.readPlan
import io.github.matthewjones372.kestrel.preview
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** Where a run got to. */
internal sealed interface Progressing {

    data class Sending(val since: Instant, val expected: Duration, val describes: String) : Progressing

    /** The plan travels with the result: what was measured is half of what a benchmark records. */
    data class Finished(val result: RunResult, val plan: Declaration) : Progressing

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
internal class Registry(
    private val kestrel: () -> Kestrel = { Kestrel(progress = Progress.silent) },
    /** Where a finished run is kept, so a restart does not lose it. */
    private val runs: Path = KEPT_RUNS,
) {

    private val byId = ConcurrentHashMap<String, Progressing>()

    /**
     * Starts one, or says why not.
     *
     * Returns as soon as the run is underway rather than when it ends. A
     * ten-minute run inside one tool call is a dead connection, a retry, and a
     * second ten-minute run against the same target.
     */
    fun start(plan: Declaration, allowance: Allowance): String {
        val simulation = plan.asSimulation(kafkaLowerings)

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

                val id = nextId()
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
                            is Ran.Result -> Progressing.Finished(ran.result, plan).also { keep(id, it) }
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

    /**
     * An id that means nothing but itself.
     *
     * A counter told every caller how many runs the server had started, which
     * is nobody's business — and, worse, two replicas started together both
     * answer `r-1`, so a caller polling one is handed another caller's run.
     * Eight hex characters collide at a rate nothing here has to care about.
     */
    private fun nextId(): String = "r-" + UUID.randomUUID().toString().replace("-", "").take(ID_LENGTH)

    /**
     * A finished run, kept where a restarted process can find it.
     *
     * The baseline format rather than a store of this module's own: it is what
     * this repository already writes a run in, it reads back as the same
     * `RunResult`, and the plan goes beside it because half of what a benchmark
     * records is what was asked for.
     *
     * Failing to write must not fail the run. The measurement is in hand by the
     * time this runs, and losing it to a full disk after it was taken would be
     * the worst trade here.
     */
    @Suppress("TooGenericExceptionCaught") // A run already measured is not lost to a failure to file it.
    private fun keep(id: String, finished: Progressing.Finished) {
        try {
            Files.createDirectories(runs)
            finished.result.writeBaseline(runs.resolve("$id$KEPT"))
            Files.writeString(runs.resolve("$id$ASKED"), finished.plan.asYaml(), Charsets.UTF_8)
        } catch (unwritable: Exception) {
            System.err.println("kestrel: could not keep $id: ${unwritable.message}")
        }
    }

    /** The run under [id], from memory or from what a previous process left. */
    private fun at(id: String): Progressing? = byId[id] ?: read(id)

    @Suppress("TooGenericExceptionCaught") // A half-written file from a killed process is not a crash.
    private fun read(id: String): Progressing.Finished? {
        val kept = runs.resolve("$id$KEPT")
        val asked = runs.resolve("$id$ASKED")
        if (!Files.exists(kept) || !Files.exists(asked)) return null

        return try {
            Progressing.Finished(readBaseline(kept), readPlan(Files.readString(asked, Charsets.UTF_8)))
                .also { byId[id] = it }
        } catch (unreadable: Exception) {
            System.err.println("kestrel: could not read $id: ${unreadable.message}")
            null
        }
    }

    /** Every id this process kept or was left, whether or not it is in memory. */
    private fun kept(): List<String> =
        runCatching { Files.list(runs).use { paths -> paths.toList() } }.getOrDefault(emptyList())
            .map { it.fileName.toString() }
            .filter { it.endsWith(KEPT) }
            .map { it.removeSuffix(KEPT) }

    /** What a run is doing, or what it concluded. */
    fun status(id: String?): String = when (val at = id?.let { at(it) }) {
        null -> content("no run `$id`; start one with `run`", failed = true)

        is Progressing.Sending -> content(
            """{"state":"sending","remaining":${at.remaining().toString().asJsonString()}}""",
        )

        is Progressing.Finished -> content(at.result.json(Density.Summary))

        is Progressing.Stopped -> content(at.why, failed = true)
    }

    /**
     * Every run this server has started or was left, newest first.
     *
     * Ordered by when each run started rather than by its id: an id that sorts
     * is a counter, and a counter is what two replicas collide on.
     */
    fun listed(): List<Pair<String, Progressing>> = (byId.keys + kept()).distinct()
        .mapNotNull { id -> at(id)?.let { id to it } }
        .sortedByDescending { (_, at) -> at.startedAt() }

    fun finished(id: String): RunResult? = (at(id) as? Progressing.Finished)?.result

    /** The run and the plan that asked for it, which is what a committed benchmark needs. */
    fun ran(id: String): Progressing.Finished? = at(id) as? Progressing.Finished

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

/** When a run began, which is the only order every kind of progress shares. */
private fun Progressing.startedAt(): Instant = when (this) {
    is Progressing.Sending -> since
    is Progressing.Finished -> result.startedAt
    is Progressing.Stopped -> Instant.EPOCH
}

/**
 * Where finished runs are kept, beside the reports.
 *
 * Its own constant rather than derived from `REPORTS`: this is a default
 * argument, so it is read while `Registry` is being constructed — and `RUNS` is
 * initialised before `REPORTS` in `Server.kt`, which made every tool fail with
 * an `ExceptionInInitializerError` the first time this was written that way.
 */
private val KEPT_RUNS: Path = Path.of("build", "reports", "kestrel", "runs")

/** Enough of a UUID that nothing here has to think about collisions. */
private const val ID_LENGTH = 8

private const val KEPT = ".kestrel"

private const val ASKED = ".plan.yaml"
