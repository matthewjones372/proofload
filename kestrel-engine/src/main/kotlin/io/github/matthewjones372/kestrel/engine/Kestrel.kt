package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Simulation
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs simulations for one test method and remembers what they measured, so a
 * failure can say which step moved without the test having to hold the result
 * itself.
 *
 * One per test. Two tests are two runs and must not share what they recorded:
 * this lives in the engine rather than in either test-framework module, so
 * neither of those has to depend on the other to get it.
 */
class Kestrel {

    // The accumulator case: a test may run more than one simulation, and the
    // extension reads these back after the method has returned. Written from
    // the test thread and read from JUnit's, hence the copy-on-write.
    private val runs = CopyOnWriteArrayList<RunResult>()

    fun run(simulation: Simulation): RunResult = simulation.run().also { runs += it }

    /** What was measured, for a failure message. Empty when nothing ran. */
    fun summary(): String? = runs.takeIf { it.isNotEmpty() }?.joinToString(separator = "\n") { it.lines() }
}

private fun RunResult.lines(): String =
    (
        listOf("kestrel: ${count.grouped()} requests, ${failed.grouped()} failed") + steps.values.map { step ->
            "  ${step.name}: ${step.count.grouped()} requests, ${step.failed.grouped()} failed, " +
                "p99 ${step.responseTime.p99} response, ${step.serviceTime.p99} service"
        }
        ).joinToString(separator = "\n")

private fun Long.grouped(): String = toString().reversed().chunked(THOUSAND).joinToString(",").reversed()

private const val THOUSAND = 3
