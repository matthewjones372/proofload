package io.github.matthewjones372.kestrel

import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * A hunt for the highest rate a scenario sustains: what to send, what it has
 * to achieve, the ceiling it will not go past, and how long each rung is held.
 *
 * A value, so [rungs] and [worstCase] answer before a request leaves.
 */
data class Search(
    val scenario: Scenario,
    val upTo: Rate,
    val holding: Duration,
    val goals: List<Goal>,
    val feeder: Feeder = Feeder.empty,
) {

    /** The ladder it climbs, lowest first; bisection then works between two of these. */
    val rungs: List<Rate> get() = (1..LADDER).map { rung -> (upTo.perSecond * rung / LADDER).perSecond }

    /**
     * The longest the holds can add up to: every rung of the ladder and every
     * bisection after it. An upper bound rather than a forecast — the search
     * stops as soon as it has the knee — and it counts the holds only, since a
     * run also waits out the users it started and that wait is the target's.
     */
    val worstCase: Duration get() = holding * (rungs.size + BISECTIONS)

    /**
     * The most the holds can still add up to once [climbed] rungs have run.
     *
     * Still a bound and still pessimistic: the ladder usually stops well short
     * of its last rung, and the bisection stops as soon as it is out of
     * halvings. A bound that is beaten leaves a reader pleasantly surprised,
     * where a forecast that is missed is a tool that lied.
     */
    fun atMostAfter(climbed: Int): Duration =
        holding * ((rungs.size - climbed).coerceAtLeast(0) + BISECTIONS)
}

/** What one rung learned: the rate it held, and the run that judged it. */
data class Rung(val rate: Rate, val result: RunResult) {

    val verdicts: List<Verdict> get() = result.verdicts

    /**
     * The rate the load actually left at: the departures the profile promised,
     * over the window plus the backlog the injector still owed at its tail.
     *
     * The same fact the void gate tests, said as a count rather than as a time
     * — a rung is void exactly when this falls a whole departure short of
     * [rate] over the window.
     */
    val offered: Rate
        get() {
            val over = result.plan.plannedWindow + result.behind.p99
            if (over <= Duration.ZERO) return rate
            return (result.plan.plannedUsers / over.toDouble(DurationUnit.SECONDS)).perSecond
        }

    val outcome: Outcome
        get() = when {
            result.lostGround() -> Outcome.Void
            verdicts.all { it.met } -> Outcome.Passed
            else -> Outcome.Failed
        }

    /** What became of a rung. */
    enum class Outcome {
        Passed,
        Failed,

        /**
         * The injector lost ground on the rate it promised, so the load was
         * never offered and nothing was learned about the target. Reporting one
         * of these as a rate the target sustained would publish the generator's
         * own ceiling under the target's name.
         */
        Void,
    }
}

/**
 * The highest rate every goal held at, and the curve that found it.
 *
 * Everything here is read off [curve]: a rate carried beside the rungs that
 * produced it is a second answer to the same question, free to disagree.
 */
data class Capacity(val curve: List<Rung>) {

    /** The highest rate every goal held at, or null when even the lowest rung missed one. */
    val rate: Rate? get() = curve.lastOrNull { it.outcome == Rung.Outcome.Passed }?.rate

    /** The goal that stopped the climb: the first one missed by the lowest rung that failed. */
    val limitedBy: Goal? get() = firstFailing?.verdicts?.firstOrNull { !it.met }?.goal

    /**
     * True when a rung ended void. [rate] is then a floor the generator
     * reached rather than a ceiling the target could not pass.
     */
    val voided: Boolean get() = curve.any { it.outcome == Rung.Outcome.Void }

    private val firstFailing: Rung? get() = curve.firstOrNull { it.outcome == Rung.Outcome.Failed }
}

/** The highest rate this scenario sustains while [expecting] holds, hunted below [upTo]. */
fun Scenario.sustainable(upTo: Rate, holding: Duration, expecting: List<Goal>): Search {
    require(upTo.perSecond > 0.0) { "upTo must be a rate above zero, but was ${upTo.perSecond}/s" }
    require(holding > Duration.ZERO) { "holding must be longer than nothing, but was $holding" }
    return Search(this, upTo, holding, expecting.toList())
}

/** The same search, with each user seeded from [feeder] before its first step. */
fun Search.fedBy(feeder: Feeder): Search = copy(feeder = feeder)

/** One rung as a run: the scenario held at [rate] for the search's window, judged by its goals. */
fun Search.at(rate: Rate): Simulation =
    Simulation(scenario, constantRate(rate, over = holding), feeder, goals)

/**
 * Climbs the ladder until a goal is missed, then bisects between the last rung
 * that passed and the first that did not.
 *
 * Pure bisection would be fewer runs, but it assumes a rate that fails means
 * every higher one does and it leaves no curve behind. The ladder buys the
 * curve at linear cost and the bisection spends its resolution at the knee.
 *
 * A rung holds for the whole window and is judged on all of it. Warm-up is
 * deliberately not a thing here: 0032 owns finding one, and inventing a second
 * rule in the meantime would leave two.
 */
fun Search.judgedBy(climbed: (Rung) -> Unit = {}, run: (Simulation) -> RunResult): Capacity {
    val told: (Simulation) -> RunResult = run
    val ladder = climb(rungs, told, emptyList(), climbed)
    return Capacity((ladder + bisect(ladder, told, climbed)).sortedBy { it.rate.perSecond })
}

private tailrec fun Search.climb(
    remaining: List<Rate>,
    run: (Simulation) -> RunResult,
    climbed: List<Rung>,
    told: (Rung) -> Unit,
): List<Rung> {
    val rate = remaining.firstOrNull() ?: return climbed
    val rung = Rung(rate, run(at(rate)))
    told(rung)
    val done = climbed + rung
    return when (rung.outcome) {
        Rung.Outcome.Void -> done

        Rung.Outcome.Passed, Rung.Outcome.Failed ->
            if (done.pastTheKnee() >= RUNGS_PAST_THE_KNEE) done else climb(remaining.drop(1), run, done, told)
    }
}

/** How many rungs have run since the first one that failed. */
private fun List<Rung>.pastTheKnee(): Int = dropWhile { it.outcome != Rung.Outcome.Failed }.size - 1

private fun Search.bisect(
    ladder: List<Rung>,
    run: (Simulation) -> RunResult,
    told: (Rung) -> Unit,
): List<Rung> {
    val knee = ladder.firstOrNull { it.outcome == Rung.Outcome.Failed } ?: return emptyList()
    val below = ladder
        .lastOrNull { it.outcome == Rung.Outcome.Passed && it.rate.perSecond < knee.rate.perSecond }
        ?.rate
        ?: 0.perSecond
    return refine(below, knee.rate, BISECTIONS, run, emptyList(), told)
}

private tailrec fun Search.refine(
    low: Rate,
    high: Rate,
    left: Int,
    run: (Simulation) -> RunResult,
    refined: List<Rung>,
    told: (Rung) -> Unit,
): List<Rung> {
    if (left == 0) return refined
    val middle = ((low.perSecond + high.perSecond) / 2).perSecond
    val rung = Rung(middle, run(at(middle)))
    told(rung)
    val done = refined + rung
    return when (rung.outcome) {
        Rung.Outcome.Void -> done
        Rung.Outcome.Passed -> refine(middle, high, left - 1, run, done, told)
        Rung.Outcome.Failed -> refine(low, middle, left - 1, run, done, told)
    }
}

// Ten, so a rung reads as a tenth of the ceiling somebody chose.
private const val LADDER = 10

// Halvings between the last passing rung and the first failing one. Five puts
// the answer inside a thirty-second of a ladder step.
private const val BISECTIONS = 5

// How far the ladder keeps climbing once a rung has failed. The shape past the
// knee is what says whether the target sheds load or collapses, and it is
// cheap to see once the load is already there.
private const val RUNGS_PAST_THE_KNEE = 2
