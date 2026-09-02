package io.github.matthewjones372.kestrel

/**
 * One statistic over a series of points, oldest first.
 *
 * The regression a pairwise comparison is blind to by construction. A p99
 * creeping two percent a point sits inside every adjacent interval — a
 * runner's own coefficient of variation is about that — so `against` answers
 * "cannot tell" every time while the number moves a third. The series is where
 * that shows.
 *
 * No fitted line and no slope: those are a model's output, and a number in a
 * report here is a measurement. What this holds is the comparisons themselves,
 * each one made by [Runs.against] on the runs of two points.
 */
data class Trend(
    val statistic: Statistic,
    /** In measurement order, oldest first. */
    val points: List<Point>,
    /** How large a change the caller declared worth acting on, passed to every comparison. */
    val acceptable: Share = Share(0.0),
) {

    init {
        require(points.size >= ENOUGH_POINTS) {
            "a trend of ${points.size} is not a trend: two points with bands is a pairwise comparison wearing a " +
                "chart, which `Runs.against` already answers"
        }
    }

    /**
     * One measurement of the series: a population, not a run.
     *
     * [label] is whatever the runs were filed under — a commit, a date, a build
     * number. The baseline format carries no such field, so it comes from
     * wherever the runs were read from rather than from inside them.
     */
    data class Point(val label: String, val runs: Runs) {

        /** Off this point's merged population, as every other reading of a statistic is. */
        fun reading(statistic: Statistic): Double? =
            statistic.samplesIn(runs.merged)?.let(statistic::read)

        /** What this point's own runs can claim, or nothing where five of them cannot. */
        fun band(statistic: Statistic): Band? = runs.band(statistic)

        /** What measured it. A series that changed runners is broken here rather than smoothed over. */
        val machine: Machine get() = runs.first.machine

        /** What a fixed, target-free measurement took here, where one was taken. */
        val probe: Probe? get() = runs.first.probe
    }

    /**
     * Two adjacent points and how the second differs from the first.
     *
     * A pair rather than a bare [Difference] because what a reader does with a
     * named step is go and look at what changed between those two labels.
     */
    data class Step(val from: Point, val to: Point, val difference: Difference)

    /**
     * The oldest point against the newest: the one comparison a creep shows in.
     *
     * Across a machine change this carries [Difference.caveat], which is the
     * honest answer rather than a refusal — the ends of a series that changed
     * runners still moved, and nothing here can say which half of it was the
     * runner.
     */
    val ends: Difference by lazy {
        points.last().runs.against(points.first().runs, statistic, acceptable)
    }

    /**
     * Every adjacent pair one machine measured both halves of, in order.
     *
     * A machine change is a break rather than a comparison: `Runs` refuses to
     * merge unlike machines, and a pair straddling one moved by an amount
     * nothing here can separate from the runner.
     */
    val pairs: List<Step> by lazy {
        points.zipWithNext()
            .filter { (from, to) -> from.machine == to.machine }
            .map { (from, to) -> Step(from, to, to.runs.against(from.runs, statistic, acceptable)) }
    }

    /** How many comparisons were made — the adjacent pairs, less the machine changes. */
    val comparisons: Int get() = pairs.size

    /** The pairs whose own runs support a move, which is where to go and look. */
    val steps: List<Step> by lazy { pairs.filter { it.difference.verdict !is Tell.CannotTell } }

    /**
     * How many of [comparisons] a series that never moved is expected to name
     * anyway, at the confidence every interval here is drawn at.
     *
     * Arithmetic on a stated assumption rather than a measurement, and printed
     * as such: thirty-nine pairs at 95% expects about two. Widening every
     * interval by the comparison count was the alternative, and it would make
     * 95% here mean something other than 95% on the run report; naming no step
     * at all throws away what a series can point at. So the count and its
     * consequence sit beside the list, where anyone can check them.
     */
    val stepsExpectedFromNoise: Double get() = comparisons * (1.0 - CONFIDENCE)
}

/** Three. Two points with bands is a pairwise comparison wearing a chart. */
private const val ENOUGH_POINTS = 3

/** The 95% every interval in this tool is drawn at, as the share it is. */
private const val CONFIDENCE = 0.95
