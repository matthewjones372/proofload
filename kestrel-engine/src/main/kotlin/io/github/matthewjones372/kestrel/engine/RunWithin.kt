package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Allowance
import io.github.matthewjones372.kestrel.Preview
import io.github.matthewjones372.kestrel.Ran
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.preview

/**
 * Runs [simulation] if [allowance] permits it, and otherwise sends nothing.
 *
 * Beside [Kestrel.run] rather than instead of it. A library call somebody wrote
 * by hand is that person's decision; this is for the callers that are programs,
 * where the rate and the host arrived from somewhere nobody read.
 *
 * The check is the same [preview] a caller can take itself, so what it is
 * refused for is what it was shown.
 */
fun Kestrel.runWithin(allowance: Allowance, simulation: Simulation): Ran =
    when (val asked = simulation.preview(allowance)) {
        is Preview.Refused -> Ran.Refused(asked.reason)
        is Preview.Allowed -> Ran.Result(run(simulation))
    }
