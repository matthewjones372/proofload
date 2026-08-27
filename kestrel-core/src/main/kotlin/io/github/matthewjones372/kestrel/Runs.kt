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
        )

private fun List<StepStats>.merged(): StepStats = StepStats(
    name = first().name,
    count = sumOf { it.count },
    ok = sumOf { it.ok },
    failures = flatMap { it.failures.entries }.groupingBy { it.key }.fold(0L) { total, e -> total + e.value },
    serviceTime = map { it.serviceTime }.merged(),
    responseTime = map { it.responseTime }.merged(),
    unmatched = sumOf { it.unmatched },
    inFlight = sumOf { it.inFlight },
)

/** The buckets of every timing here added together, with the percentiles read off the sum. */
private fun List<Timing>.merged(): Timing = flatMap { it.distribution }
    .groupingBy { it.upperBound }
    .fold(0L) { counted, bucket -> counted + bucket.count }
    .map { (upperBound, count) -> Bucket(upperBound, count) }
    .sortedBy { it.upperBound }
    .timing()
