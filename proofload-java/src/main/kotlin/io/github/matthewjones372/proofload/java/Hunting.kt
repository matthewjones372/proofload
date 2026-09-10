package io.github.matthewjones372.proofload.java

import io.github.matthewjones372.proofload.Capacity
import io.github.matthewjones372.proofload.Goal
import io.github.matthewjones372.proofload.Rung
import io.github.matthewjones372.proofload.Scenario
import io.github.matthewjones372.proofload.Search
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.sustainable
import io.github.matthewjones372.proofload.warmingUp
import java.time.Duration as JavaDuration

/**
 * The capacity search, reached where its value classes are still nameable.
 *
 * `Search` and `Capacity` cross a signature intact, but every call that builds
 * one takes a `Rate` or a `kotlin.time.Duration` and every rate read off one
 * returns a `Rate`, so [Searches] is the Java source over this.
 */
internal object Hunts {

    @JvmStatic
    @JvmName("sustainable")
    fun sustainable(scenario: Scenario, perSecond: Double, holding: JavaDuration, expecting: List<Goal>): Search =
        scenario.sustainable(perSecond.perSecond, holding.asProofload(), expecting)

    @JvmStatic
    @JvmName("warmingUp")
    fun warmingUp(search: Search, over: JavaDuration): Search = search.warmingUp(over.asProofload())

    @JvmStatic
    @JvmName("capacityRate")
    fun capacityRate(capacity: Capacity): Any? = capacity.rate

    @JvmStatic
    @JvmName("rungRate")
    fun rungRate(rung: Rung): Any = rung.rate

    @JvmStatic
    @JvmName("offered")
    fun offered(rung: Rung): Any = rung.offered
}
