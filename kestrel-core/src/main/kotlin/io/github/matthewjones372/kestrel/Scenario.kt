package io.github.matthewjones372.kestrel

import java.util.Collections
import kotlin.time.Duration

/**
 * A step's name, declared once and shared by the scenario that defines the step
 * and every assertion about it.
 *
 * The same argument as [SessionKey], one layer out: a name written twice is a
 * name that can disagree with itself, and a scenario split across files has
 * something to import rather than a literal to copy.
 */
@JvmInline
value class StepName(val name: String)

fun step(name: String): StepName = StepName(name)

/** One thing a virtual user does, named so a report has a row that is not a URL. */
sealed interface Step {

    val name: String

    data class Exec(override val name: String, val action: Action) : Step

    /**
     * A step that departs and does not wait. The publish is timed here; the
     * answer is matched at the sink the simulation names, under a step of its
     * own, so a slow producer and a slow pipeline are never added together.
     */
    data class Emit(override val name: String, val action: Action, val correlation: Correlation) : Step

    /**
     * Think time: the user is doing something that is not this system, so the
     * wait is a gap between steps rather than a step with a timing.
     *
     * It carries no measurement and never becomes a row in a report, because
     * every number a report prints under a step name is what the target took.
     * A pause under one would be a percentile nobody waited on the target for —
     * either diluting the tail with time it did not cause, or, read as latency,
     * inventing seconds of slowness out of a user reading a page. The name is
     * here for the interface and for inspecting a scenario; nothing is keyed by
     * it.
     */
    data class Pause(val duration: Duration) : Step {

        init {
            require(duration >= Duration.ZERO) { "a pause cannot run backwards, but was $duration" }
        }

        override val name: String get() = "pause"
    }
}

/**
 * What a virtual user does, in order. A value: nothing here runs, so a
 * scenario can be built, inspected, split across files and compared before
 * anything is sent.
 */
data class Scenario(val name: String, val steps: List<Step>)

class ScenarioBuilder internal constructor(private val name: String) {

    private val steps = mutableListOf<Step>()

    fun exec(name: String, action: Action) {
        steps += Step.Exec(name, action)
    }

    fun exec(name: String, block: StepScope.() -> Unit) {
        steps += Step.Exec(name, action(block))
    }

    fun exec(name: StepName, action: Action) {
        steps += Step.Exec(name.name, action)
    }

    fun exec(name: StepName, block: StepScope.() -> Unit) {
        steps += Step.Exec(name.name, action(block))
    }

    fun pause(duration: Duration) {
        steps += Step.Pause(duration)
    }

    fun emit(name: String, action: Action, keyedBy: Correlation) {
        steps += Step.Emit(name, action, keyedBy)
    }

    fun emit(name: StepName, action: Action, keyedBy: Correlation) {
        steps += Step.Emit(name.name, action, keyedBy)
    }

    // Frozen rather than copied: a copy is still an ArrayList to a Java caller
    // holding the List, and a scenario that can be added to after it is built
    // is not the value the rest of this design assumes.
    internal fun build(): Scenario = Scenario(name, Collections.unmodifiableList(steps.toList()))
}

fun scenario(name: String, block: ScenarioBuilder.() -> Unit): Scenario =
    ScenarioBuilder(name).apply(block).build()
