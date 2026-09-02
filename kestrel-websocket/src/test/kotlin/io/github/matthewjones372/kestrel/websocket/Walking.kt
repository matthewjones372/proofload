package io.github.matthewjones372.kestrel.websocket

import io.github.matthewjones372.kestrel.SampleSink
import io.github.matthewjones372.kestrel.Scenario
import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.Step
import io.github.matthewjones372.kestrel.StepResult
import io.github.matthewjones372.kestrel.StepScope

/**
 * Runs the steps in order, stopping at the first failure, the way the engine
 * does.
 *
 * A scenario is a tree rather than a list, so this walks one. It used to cast
 * every step to [Step.Exec], which held only for as long as `repeat` inside a
 * scenario meant `kotlin.repeat` and unrolled the body; the moment
 * `ScenarioBuilder.repeat` existed, that cast met the `Step.Repeat` it builds.
 *
 * This module is a leaf on core and cannot reach the engine, so the walk is
 * duplicated here rather than shared. The `when` is exhaustive over the sealed
 * type with no `else`, so a new form of step fails to compile here rather than
 * being silently skipped.
 */
internal fun Scenario.walk(samples: SampleSink? = null): StepResult =
    steps.walk(StepResult.Ok(Session.empty), samples)

private fun List<Step>.walk(from: StepResult, samples: SampleSink?): StepResult = fold(from) { carried, step ->
    when (carried) {
        is StepResult.Failed -> carried
        is StepResult.Ok -> step.walk(carried, samples)
    }
}

private fun Step.walk(carried: StepResult.Ok, samples: SampleSink?): StepResult = when (this) {
    // The scope built here rather than by `run(session)`, so a body's own
    // samples have somewhere to go: the engine does the same, and this module
    // cannot reach the engine.
    is Step.Exec -> StepScope(carried.session, samples).also(action::run).result()

    is Step.Emit -> StepScope(carried.session, samples).also(action::run).result()

    is Step.Repeat -> (1..times).fold<Int, StepResult>(carried) { each, _ ->
        if (each is StepResult.Ok) steps.walk(each, samples) else each
    }

    // One pass. A test here asserts what a body did, not how long a clock ran
    // for, and looping on a wall clock would make these tests take as long as
    // the window says rather than as long as the work does.
    is Step.During -> steps.walk(carried, samples)

    is Step.When -> if (predicate(carried.session)) steps.walk(carried, samples) else carried

    // Nothing to run and nothing to wait for: a pause is time a user spends
    // reading, and no assertion in this module is about the clock.
    is Step.Pause -> carried
}
