package io.github.matthewjones372.kestrel

import kotlin.time.Duration

/**
 * How often virtual users arrive. A value class rather than a bare `Double`,
 * so a per-minute figure cannot be handed to something expecting a per-second
 * one and be quietly believed.
 */
@JvmInline
value class Rate private constructor(val perSecond: Double) {

    companion object {
        internal fun ofPerSecond(perSecond: Double): Rate = Rate(perSecond)
    }
}

val Number.perSecond: Rate get() = Rate.ofPerSecond(toDouble())

val Number.perMinute: Rate get() = Rate.ofPerSecond(toDouble() / SECONDS_PER_MINUTE)

/** A sink drained for the answers a run's emit steps departed with, and how long the run waits for them. */
data class Completing(val step: String, val from: Completions, val drainingFor: Duration)

/** A scenario, the rate it is sent at and what its users start with: one journey of a run. */
data class Arm(
    val scenario: Scenario,
    val profile: InjectionProfile,
    val feeder: Feeder = Feeder.empty,
    /**
     * What this arm's drawn waits come from, where it draws any.
     *
     * Null is not a default seed: it means nothing here draws, and a scenario
     * that does draw is refused without one. An unseeded random run is not one
     * anybody can reproduce, which is the same rule `randomized` already holds
     * arrivals to.
     */
    val thinkSeed: Long? = null,
)

/** The arms sent together, what they have to achieve and the sink they are drained into: a run, as one value. */
data class Simulation(
    val arms: List<Arm>,
    val goals: List<Goal> = emptyList(),
    val completing: Completing? = null,
    val warmUp: WarmUp? = null,
    /** Which injector this is, where the run is split across several. */
    val shard: Shard? = null,
) {

    constructor(
        scenario: Scenario,
        profile: InjectionProfile,
        feeder: Feeder = Feeder.empty,
        goals: List<Goal> = emptyList(),
        completing: Completing? = null,
        warmUp: WarmUp? = null,
    ) : this(listOf(Arm(scenario, profile, feeder)), goals, completing, warmUp)

    init {
        require(arms.isNotEmpty()) { "a simulation sends at least one arm" }
        val shared = arms
            .flatMap { arm -> arm.scenario.stepNames.distinct().map { step -> step to arm.scenario.name } }
            .groupBy({ (step, _) -> step }, { (_, scenario) -> scenario })
            .filterValues { it.size > 1 }
        // A step name is one row of the report. Two arms sharing one would
        // either merge into a row describing neither or be qualified behind the
        // caller's back, which breaks reading a row back by the name written.
        require(shared.isEmpty()) {
            shared.entries.joinToString("; ", postfix = ": rename one of them") { (step, scenarios) ->
                "step \"$step\" is in ${scenarios.joinToString(" and ")}"
            }
        }
    }

    /** The first arm's rate line; a mix has one per arm, on [Arm]. */
    val profile: InjectionProfile get() = arms.first().profile

    /** The first arm's data, likewise. */
    val feeder: Feeder get() = arms.first().feeder

    /** How long the run lasts: the arms depart together, so the longest of them. */
    val over: Duration get() = arms.maxOf { it.profile.over }
}

/** How many users this run sends, every arm counted, before it sends any. */
fun Simulation.userCount(): Long = arms.sumOf { it.profile.userCount() }

/**
 * Both sides' arms in one run, judged by both sides' goals.
 *
 * A drained sink belongs to the run rather than to an arm, so the left-hand
 * side's is the one kept.
 */
operator fun Simulation.plus(other: Simulation): Simulation =
    Simulation(arms + other.arms, goals + other.goals, completing ?: other.completing)

/**
 * The same run, with [from] drained for the answers its emit steps departed
 * with and each one recorded under [step].
 *
 * [drainingFor] is required and has no default. A wait chosen for the caller
 * would silently turn records the run lost into records it merely did not wait
 * for, or the other way about, and telling those two apart is what this is for.
 */
fun Simulation.completing(step: String, from: Completions, drainingFor: Duration): Simulation =
    copy(completing = Completing(step, from, drainingFor))

fun Simulation.completing(step: StepName, from: Completions, drainingFor: Duration): Simulation =
    completing(step.name, from, drainingFor)

/** What this run has to achieve to count as good. */
fun Simulation.expecting(vararg goals: Goal): Simulation = copy(goals = this.goals + goals)

/** What this run is asking for, before any of it happens, every arm of it. */
fun Simulation.plan(): Plan = Plan(
    arms = arms.map { arm ->
        PlannedArm(
            scenario = arm.scenario.name,
            steps = arm.scenario.stepNames,
            profile = arm.profile,
            pauses = arm.scenario.pauses,
            thinkTimes = arm.scenario.thinkTimes,
            thinkSeed = arm.thinkSeed,
        )
    },
    goals = goals,
    warmUp = warmUp,
)

/**
 * The same run, with [over] of load sent before the measurement starts and
 * recorded nowhere.
 *
 * Each arm is held at the rate its own shape opens at, so a ramp warms at the
 * load its first measured departures meet rather than at a peak the run has
 * not climbed to yet.
 *
 * Named for what it does rather than `warmingUp(for = ...)`: `for` is a hard
 * keyword and would need backticks at every call site.
 */
fun Simulation.warmingUp(over: Duration): Simulation = copy(warmUp = WarmUp(over))

/**
 * Refuses a run whose waits are drawn from nothing.
 *
 * Called by an engine before it departs anybody, rather than by the
 * constructor: `at(...).thinkingFrom(seed)` builds the simulation before it
 * carries the seed, so a constructor that refused would refuse the shape this
 * is meant to be written in. A run that departs and only then finds it cannot
 * reproduce itself has already spent the window it was measuring, which is why
 * this is not left to the first pause either.
 */
fun Simulation.requireSeededThinking() {
    val unseeded = arms.filter { it.scenario.drawsThinkTime && it.thinkSeed == null }
    require(unseeded.isEmpty()) {
        unseeded.joinToString(", ", postfix = ": add thinkingFrom(seed)") { arm ->
            "\"${arm.scenario.name}\" draws its think time and has no seed"
        }
    }
}

/**
 * The same run, with every arm's drawn waits coming from [seed].
 *
 * On the run beside `fedBy` rather than on each pause: a scenario with four
 * drawn pauses would otherwise be reproducible in four places, and a reader
 * would have to collect them to know what to write down. Each arm draws from
 * this seed mixed with its own index, as a staged profile seeds its stages.
 */
fun Simulation.thinkingFrom(seed: Long): Simulation =
    copy(arms = arms.mapIndexed { index, arm -> arm.copy(thinkSeed = seed + index) })

/** The same run, with each user of every arm seeded from [feeder] before its first step. */
fun Simulation.fedBy(feeder: Feeder): Simulation = copy(arms = arms.map { it.copy(feeder = feeder) })

/** Gatling's `setUp` / `inject` / `protocols`, in one call. */
fun Scenario.at(rate: Rate, over: Duration): Simulation = Simulation(this, constantRate(rate, over))

fun Scenario.injecting(profile: InjectionProfile): Simulation = Simulation(this, profile)

private const val SECONDS_PER_MINUTE = 60.0
