package io.github.matthewjones372.kestrel

/**
 * What sends a simulation: declared here, where the description lives, so a
 * scenario does not belong to whichever module happens to run it.
 *
 * Blocking rather than suspending. A JUnit test method has no continuation to
 * supply one, and core can declare `suspend` but not the builders that call it,
 * so a suspending method here would be one core could not help anybody call.
 */
fun interface Engine {

    fun run(simulation: Simulation): RunResult
}
