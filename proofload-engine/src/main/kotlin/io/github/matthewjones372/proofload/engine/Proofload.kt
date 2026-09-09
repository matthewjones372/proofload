package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Capacity
import io.github.matthewjones372.proofload.Engine
import io.github.matthewjones372.proofload.Exclusivity
import io.github.matthewjones372.proofload.Feeder
import io.github.matthewjones372.proofload.Floor
import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.Scenario
import io.github.matthewjones372.proofload.Search
import io.github.matthewjones372.proofload.Simulation
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs simulations for one test method and remembers what they measured, so a
 * failure can say which step moved without the test having to hold the result
 * itself.
 *
 * One per test. Two tests are two runs and must not share what they recorded:
 * this lives in the engine rather than in either test-framework module, so
 * neither of those has to depend on the other to get it.
 *
 * [engine] is what sends each simulation. It lives here rather than in core
 * because a default has to name an implementation, and core naming one would be
 * the coupling the interface exists to remove.
 *
 * The default takes the machine for the duration of a run, so two tests that
 * start one at the same moment measure it one after the other instead of
 * measuring each other.
 */
class Proofload(
    private val engine: Engine = VirtualThreads().exclusive(),
    /**
     * What a capacity search says while it climbs. Held here rather than
     * reached for through [engine], because a search is not a run and the seam
     * 0051 kept to one method has nothing to say about one.
     */
    private val progress: Progress = Progress.lines(),
) {

    /**
     * The default engine, told what to say while a run is going. Progress is
     * the engine's to report — it is the thing that knows a departure left —
     * so naming a reporter names an engine rather than widening the seam that
     * 0051 kept to one method.
     */
    constructor(progress: Progress) : this(VirtualThreads(progress).exclusive(progress), progress)

    // The accumulator case: a test may run more than one simulation, and the
    // extension reads these back after the method has returned. Written from
    // the test thread and read from JUnit's, hence the copy-on-write.
    private val runs = CopyOnWriteArrayList<RunResult>()

    fun run(simulation: Simulation): RunResult = engine.run(simulation).also { runs += it }

    /**
     * Hunts for the rate the scenario sustains. Every rung it ran is kept, so
     * a failure can show the curve rather than only the rate read off the end
     * of it.
     *
     * The machine is held across the whole search rather than rung by rung, so
     * a curve is one machine's answer and not two interleaved runs'.
     */
    fun run(search: Search): Capacity =
        exclusively(Exclusivity.Running, progress) { search.reported(progress) { rung -> run(rung) } }

    /**
     * Walks one user through [scenario] and prints what each step did.
     *
     * Nothing is recorded and nothing is returned: a trace is for reading, so
     * it is absent from [summary] and from anything a report is made of.
     */
    fun trace(scenario: Scenario, feeder: Feeder = Feeder.empty) = scenario.trace(feeder)

    /**
     * What this machine can resolve, measured once per JVM and kept afterwards.
     * A floor is a property of the machine rather than of a run, and one
     * measured between two runs would be measuring the drift it is there to
     * bound.
     */
    fun calibrate(): Floor = exclusively(Exclusivity.Calibrating, progress) { machineFloor() }

    /** What was measured, for a failure message. Empty when nothing ran. */
    fun summary(): String? = runs.takeIf { it.isNotEmpty() }?.joinToString(separator = "\n") { it.lines() }
}

private fun RunResult.lines(): String =
    (
        listOf("proofload: ${count.grouped()} requests, ${failed.grouped()} failed") + steps.values.map { step ->
            "  ${step.name}: ${step.count.grouped()} requests, ${step.failed.count.grouped()} failed, " +
                "p99 ${step.responseTime.p99} response, ${step.serviceTime.p99} service"
        }
        ).joinToString(separator = "\n")

private fun Long.grouped(): String = toString().reversed().chunked(THOUSAND).joinToString(",").reversed()

private const val THOUSAND = 3
