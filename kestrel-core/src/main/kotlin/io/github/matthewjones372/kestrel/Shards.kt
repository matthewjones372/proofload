package io.github.matthewjones372.kestrel

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Every injector of one run, merged into the result one JVM would have
 * measured.
 *
 * Its own type rather than a flag on [Runs], because the two are different
 * experiments wearing the same shape. *N* shards are one run measured in
 * pieces, and merging them answers the question the run asked; *M* runs are
 * the same run repeated, and merging those answers what varies between
 * processes. Handed a set of shards, [Runs] would offer an interval across
 * four quarters of one run and call it process variance.
 *
 * The set must be whole: exactly injectors `0` until `of`, one *N*, one
 * instant. A merge of three quarters is a smaller experiment than the one
 * somebody asked for, and nothing in the numbers says so.
 */
data class Shards(val each: List<RunResult>) {

    init {
        require(each.isNotEmpty()) { "there are no injectors here to merge" }
        val shards = each.map { run ->
            requireNotNull(run.shard) {
                "a run with no shard was not one injector of several; it is a whole run, and $NOT_ONE_OF"
            }
        }
        val counts = shards.map { it.of }.distinct()
        require(counts.size == 1) { "these injectors do not agree how many there are: ${counts.sorted()}" }
        val instants = shards.map { it.startingAt }.distinct()
        require(instants.size == 1) {
            "these injectors were given different instants to start at, so they are not one run: " +
                instants.sorted().joinToString()
        }

        val of = counts.single()
        val sent = shards.map { it.index }.sorted()
        // Named rather than counted: someone looking at this is looking at N
        // hosts, and which one to go and look at is the whole answer.
        val missing = (0 until of).filterNot { it in sent }
        val twice = sent.filter { index -> sent.count { it == index } > 1 }.distinct()
        require(missing.isEmpty() && twice.isEmpty()) {
            listOfNotNull(
                "injector ${missing.joinToString()} of $of wrote nothing".takeIf { missing.isNotEmpty() },
                "injector ${twice.joinToString()} of $of wrote more than once".takeIf { twice.isNotEmpty() },
            ).joinToString(prefix = "this is not a whole run: ", postfix = ", so $LESS_THAN_ASKED")
        }

        val differences = each.drop(1).flatMapIndexed { at, run -> run.unlikeInjector(each.first(), sent[at + 1]) }
        require(differences.isEmpty()) {
            "these injectors did not measure one run: ${differences.joinToString()}"
        }
    }

    /** How many injectors sent this run. */
    val of: Int get() = each.first().shard?.of ?: 1

    /**
     * The injector that came off worst on its own schedule.
     *
     * Named rather than merely folded into [merged], because it is the one a
     * reader has to go and look at: a distributed run is only as good as the
     * host that struggled.
     */
    val worst: RunResult get() = each.maxBy { it.behind.p99 }

    /**
     * How far apart the injectors actually started, widest first to last.
     *
     * Reported rather than refused. 0025 fixes the timeline's resolution at
     * one second, so skew below that smears the picture without touching a
     * percentile: every sample is still two readings of one injector's own
     * monotonic clock.
     */
    val startedApart: Duration
        get() = (each.maxOf { it.startedAt.toEpochMilli() } - each.minOf { it.startedAt.toEpochMilli() }).milliseconds

    /**
     * Whether any injector lost ground on the schedule it was keeping.
     *
     * Any, not the pool: the run is the union of what the injectors sent, so
     * one host falling behind means the load the profile named is not the load
     * that left, however comfortable the other three were. Each is judged
     * against its own spacing, which is [Plan.plannedInterval] times [of].
     */
    fun lostGround(): Boolean = each.any { it.lostGround() }

    /**
     * Every injector's samples as the run they were one run of: the buckets
     * added and the percentiles read off the sum, exactly as [Runs] merges.
     *
     * What differs is the three things a partition changes. The plan is the
     * whole plan, which every injector carried unmodified, so the merged run
     * says what was asked for rather than what one quarter of it was. There is
     * no shard, because a run measured in four pieces is still one run.
     * Lateness and stalls come from the worst injector rather than the pool:
     * shard *k*'s departures are *N* intervals apart, so pooling them against
     * the whole run's spacing calls a rung void when every injector kept time.
     */
    val merged: RunResult by lazy {
        val whole = Runs(each.map { it.copy(shard = null, injectors = 1) }).merged
        whole.copy(
            behind = worst.behind,
            hiccups = worst.hiccups,
            // What the injectors were told to send between them, so the
            // spacing `lostGround` reads off this result is the one the worst
            // injector's lateness was measured against.
            injectors = of,
            // Not merged, and not the profile's either: shards interleave into
            // the sequence one JVM would have produced, but each one's own
            // gaps are N times the run's, so a spread read off them is nobody's
            // measurement. Each injector keeps its own on `each`.
            arrivals = Arrivals.none,
        )
    }
}

/** Where two injectors disagree about what they were measuring, in [Runs]'s vocabulary. */
private fun RunResult.unlikeInjector(first: RunResult, index: Int): List<String> =
    plan.unlike(first.plan).map { "injector $index was not asked to do the same thing as the first: $it" } +
        listOfNotNull(
            "injector $index was measured on $machine and the first on ${first.machine}"
                .takeIf { machine != first.machine },
        )

private const val LESS_THAN_ASKED = "merging it would report a smaller experiment as the one asked for"

private const val NOT_ONE_OF = "merging it with others would report a load none of them offered"
