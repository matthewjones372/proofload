package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Feeder
import io.github.matthewjones372.kestrel.Scenario
import io.github.matthewjones372.kestrel.stepNames
import kotlin.time.Duration

/**
 * Walks one user through the scenario, printing what each step did, and returns
 * nothing.
 *
 * A diagnostic, not a measurement. One pass on a cold JVM with no schedule
 * behind it has no number worth reporting, and a result handed back here would
 * reach `writeHtmlReport` looking like one that had.
 */
fun Scenario.trace(feeder: Feeder = Feeder.empty) {
    println("kestrel: trace $name")
    // The walk a run uses, with a sink that prints where a run's records: a
    // diagnostic taking a route of its own can disagree with the run it is
    // there to explain.
    runOneUser(
        printedTo(stepNames.maxOfOrNull { it.length } ?: 0),
        runStart = System.nanoTime(),
        schedulingDelay = Duration.ZERO,
        departure = Duration.ZERO,
        started = feeder.forUser(0),
        drain = null,
    )
}

// Names are padded to the widest the scenario declares, so what happened reads
// down a column. No time is printed: one request from a cold JVM is not a
// measurement, and a number here would be quoted as though it were.
private fun printedTo(width: Int): StepSink = StepSink { step, failure, _, _, _ ->
    println("kestrel:   ${step.padEnd(width)}  ${failure.outcome()}")
}

// Says what the engine did about the failure as well as what it was: the steps
// after it are not walked, and a reader who does not know that reads the lines
// that are missing as a trace that stopped early.
private fun String?.outcome(): String = this?.let { "FAILED $it — user abandoned here" } ?: "ok"
