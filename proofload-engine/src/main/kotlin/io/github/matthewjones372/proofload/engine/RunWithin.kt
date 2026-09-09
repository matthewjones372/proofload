package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Allowance
import io.github.matthewjones372.proofload.Preview
import io.github.matthewjones372.proofload.Ran
import io.github.matthewjones372.proofload.Simulation
import io.github.matthewjones372.proofload.preview

/**
 * Runs [simulation] if [allowance] permits it, and otherwise sends nothing.
 *
 * Beside [Proofload.run] rather than instead of it. A library call somebody wrote
 * by hand is that person's decision; this is for the callers that are programs,
 * where the rate and the host arrived from somewhere nobody read.
 *
 * The check is the same [preview] a caller can take itself, so what it is
 * refused for is what it was shown.
 */
fun Proofload.runWithin(allowance: Allowance, simulation: Simulation): Ran =
    when (val asked = simulation.preview(allowance)) {
        is Preview.Refused -> Ran.Refused(asked.reason)
        is Preview.Allowed -> Ran.Result(run(simulation))
    }
