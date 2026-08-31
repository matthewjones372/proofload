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

/** A scenario, the rate it is sent at and what its users start with: a run, as one value. */
data class Simulation(
    val scenario: Scenario,
    val profile: InjectionProfile,
    val feeder: Feeder = Feeder.empty,
    val goals: List<Goal> = emptyList(),
    val completing: Completing? = null,
)

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

/** What this run is asking for, before any of it happens. */
fun Simulation.plan(): Plan = Plan(
    scenario = scenario.name,
    steps = scenario.stepNames,
    profile = profile,
    goals = goals,
)

/** The same run, with each user seeded from [feeder] before its first step. */
fun Simulation.fedBy(feeder: Feeder): Simulation = copy(feeder = feeder)

/** Gatling's `setUp` / `inject` / `protocols`, in one call. */
fun Scenario.at(rate: Rate, over: Duration): Simulation = Simulation(this, constantRate(rate, over))

fun Scenario.injecting(profile: InjectionProfile): Simulation = Simulation(this, profile)

private const val SECONDS_PER_MINUTE = 60.0
