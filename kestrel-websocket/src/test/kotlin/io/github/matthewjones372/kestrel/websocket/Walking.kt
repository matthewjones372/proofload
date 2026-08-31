package io.github.matthewjones372.kestrel.websocket

import io.github.matthewjones372.kestrel.Scenario
import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.Step
import io.github.matthewjones372.kestrel.StepResult

/** Runs the steps in order, stopping at the first failure, the way the engine does. */
internal fun Scenario.walk(): StepResult =
    steps.fold<Step, StepResult>(StepResult.Ok(Session.empty)) { carried, step ->
        when (carried) {
            is StepResult.Failed -> carried
            is StepResult.Ok -> (step as Step.Exec).action.run(carried.session)
        }
    }
