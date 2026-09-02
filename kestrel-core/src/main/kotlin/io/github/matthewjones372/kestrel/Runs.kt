package io.github.matthewjones372.kestrel

/**
 * Several runs of one plan, kept individually and as one merged result.
 *
 * Both, because they answer different questions. [merged] is the population a
 * percentile should be read off, and it hides that ten processes produced it;
 * [each] is what an interval across processes is made of.
 *
 * Unlike runs are refused rather than pooled. A comparison of two plans can
 * keep the populations apart and say so, but a merge cannot: it would answer
 * one question with samples taken from another.
 */
data class Runs(val each: List<RunResult>) {

    init {
        require(each.isNotEmpty()) { "there are no runs here to merge" }
        val differences = each.drop(1).flatMapIndexed { index, run -> run.unlike(each.first(), index + 2) }
        require(differences.isEmpty()) {
            "these runs are not one population, and merging them would pool two: ${differences.joinToString()}"
        }
    }

    val size: Int get() = each.size

    /**
     * The first run, kept rather than dropped for being the cold one. Naming
     * it is what lets a caller discard it deliberately, on the record.
     */
    val first: RunResult get() = each.first()

    /** Every run after the first. */
    val afterFirst: List<RunResult> get() = each.drop(1)

    /**
     * Every run's samples as one result: the buckets are added and the
     * percentiles read off the sum. A percentile of the whole population
     * cannot be recovered from the percentiles of the runs that made it, so
     * nothing here reads theirs.
     *
     * The timeline is superimposed rather than laid end to end: second *n*
     * here is second *n* of every run, so ten two-minute runs give two minutes
     * of ten times the load. Concatenating would describe a different
     * experiment from the percentiles printed beside it, which are already the
     * runs pooled. The cost is that a run slow only in its own third second is
     * diluted by nine that were not — the same trade the percentiles make, and
     * [each] still holds every run on its own.
     */
    val merged: RunResult by lazy {
        RunResult(
            startedAt = each.minOf { it.startedAt },
            steps = each.flatMap { it.steps.values }.groupBy { it.name }.mapValues { (_, step) -> step.merged() },
            behind = each.map { it.behind }.merged(),
            hiccups = each.map { it.hiccups }.merged(),
            plan = first.plan,
            // Not merged: a gap between one run's last departure and the next
            // run's first is one nobody scheduled, and the file format a run
            // is read back from does not carry arrivals at all. Each run keeps
            // its own on `each`.
            arrivals = Arrivals.none,
            machine = first.machine,
            timeline = each.map { it.timeline }.superimposed(),
            // Superimposed with the timeline it is indexed with: second n of
            // every run is second n here, so the lateness of second n is every
            // run's lateness in that second pooled.
            latePerSecond = each.map { it.latePerSecond }.superimposedLateness(),
        )
    }

    companion object {
        fun of(vararg runs: RunResult): Runs = Runs(runs.toList())
    }
}

/**
 * What [position] in a set of runs was asked for or measured on that the first
 * run was not, in the vocabulary a refused comparison uses.
 */
private fun RunResult.unlike(first: RunResult, position: Int): List<String> =
    plan.unlike(first.plan).map { "run $position was not asked to do the same thing as the first: $it" } +
        listOfNotNull(
            "run $position was measured on $machine and the first on ${first.machine}"
                .takeIf { machine != first.machine },
            // Lengths that merely differ are padded rather than refused; a run
            // carrying no timeline at all is a different fact, and padding it
            // would invent seconds nobody has the data for.
            "run $position has ${timeline.asLength()} and the first has ${first.timeline.asLength()}"
                .takeIf { timeline.isEmpty() != first.timeline.isEmpty() },
        )

/**
 * A run read back from a baseline file has no timeline at all, because the
 * format does not carry one; that is a different fact from a run that measured
 * no seconds, and the refusal above should not read as if it were.
 */
private fun List<Second>.asLength(): String = if (isEmpty()) "no timeline" else "$size seconds of timeline"

private fun List<StepStats>.merged(): StepStats = StepStats(
    name = first().name,
    ok = map { it.ok }.mergedOutcome(),
    failed = map { it.failed }.mergedOutcome(),
    serviceTime = map { it.serviceTime }.merged(),
    responseTime = map { it.responseTime }.merged(),
    // Summed like the requests they sit behind: two runs' round trips are the
    // round trips of both.
    attempts = sumOf { it.attempts },
    unmatched = sumOf { it.unmatched },
    inFlight = sumOf { it.inFlight },
    timeline = map { it.timeline }.superimposed(),
)

/**
 * Second *n* of every run as second *n* of one: the coarse histograms added and
 * the percentiles read off the sum, which is the only merge of a second there
 * is. Averaging two seconds' percentiles would answer with a number neither of
 * them measured.
 *
 * A run that stopped short of the longest is padded with the zero seconds it
 * recorded rather than refused. 0025 already writes an interior quiet second as
 * a zero, on the grounds that a gap in a line is information; the only reason a
 * single run does not pad its trailing silence is that a single run has no
 * defined end, and a merge does — the longest run. A zero here is therefore the
 * same measurement those interior zeros are, not an invention.
 *
 * Nor is a difference in length a difference in the experiment. Unlike plans
 * are already refused and the plan carries the profile, so every run here was
 * asked for the same length; what is left is jitter in where the last response
 * landed, which the plan check has already seen.
 */

/** Second *n* of every run's lateness as second *n* of one, on [superimposed]'s reasoning. */
private fun List<List<Timing>>.superimposedLateness(): List<Timing> {
    if (all { it.isEmpty() }) return emptyList()
    return (0 until maxOf { it.size }).map { second ->
        mapNotNull { it.getOrNull(second) }.merged()
    }
}

private fun List<List<Second>>.superimposed(): List<Second> =
    (0 until maxOf { it.size }).map { second ->
        val counted = mapNotNull { it.getOrNull(second) }
        Second(
            okServiceTime = counted.map { it.okServiceTime }.merged(),
            failedServiceTime = counted.map { it.failedServiceTime }.merged(),
            okResponseTime = counted.map { it.okResponseTime }.merged(),
            failedResponseTime = counted.map { it.failedResponseTime }.merged(),
        )
    }

private fun List<Outcome>.mergedOutcome(): Outcome = Outcome(
    serviceTime = map { it.serviceTime }.merged(),
    responseTime = map { it.responseTime }.merged(),
    reasons = flatMap { it.reasons.entries }.groupingBy { it.key }.fold(0L) { total, e -> total + e.value },
)

/** The buckets of every timing here added together, with the percentiles read off the sum. */
internal fun List<Timing>.merged(): Timing = flatMap { it.distribution }
    .groupingBy { it.upperBound }
    .fold(0L) { counted, bucket -> counted + bucket.count }
    .map { (upperBound, count) -> Bucket(upperBound, count) }
    .sortedBy { it.upperBound }
    .timing()
