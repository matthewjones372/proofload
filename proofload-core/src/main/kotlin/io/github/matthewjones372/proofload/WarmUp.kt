package io.github.matthewjones372.proofload

import kotlin.time.Duration

/**
 * Load sent before the measurement starts, and recorded nowhere.
 *
 * A cold JVM's first departures pay for class loading and the JIT, and they
 * pay for it in the numbers: a 45-a-second run reported a p99 lateness of 612
 * milliseconds from a cold start and 897 microseconds behind a throwaway run,
 * which is the difference between the schedule goal met and missed. Warming is
 * written by hand in five places in this repository and in none of them is it
 * a value anything can print, compare or count.
 *
 * The length is the caller's, and there is no default: a tool that warms
 * unless told otherwise changes what every run already written measures, for
 * a length nobody declared. Detecting the moment a JVM is warm is what this
 * deliberately does not attempt — the published estimates get it wrong by tens
 * of seconds — so a stated length is part of the experiment, printed with the
 * rest of it and never claimed to be sufficient.
 */
data class WarmUp(val over: Duration) {

    init {
        require(over > Duration.ZERO) { "a warm-up runs for some time, but this warm-up was over $over" }
    }
}
