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

/** A scenario and the rate it is sent at: everything a run needs, as one value. */
data class Simulation(val scenario: Scenario, val profile: InjectionProfile)

/** Gatling's `setUp` / `inject` / `protocols`, in one call. */
fun Scenario.at(rate: Rate, over: Duration): Simulation = Simulation(this, constantRate(rate, over))

fun Scenario.injecting(profile: InjectionProfile): Simulation = Simulation(this, profile)

private const val SECONDS_PER_MINUTE = 60.0
