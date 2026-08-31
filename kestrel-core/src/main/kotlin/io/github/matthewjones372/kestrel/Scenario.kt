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

/**
 * A node of what a virtual user does. [Exec] and [Emit] are the named leaves a
 * report has a row for; [Repeat], [During] and [When] hold steps rather than
 * being one,
 * so a journey that loops or asks a question is a tree that can be inspected
 * rather than Kotlin control flow that ran while the scenario was built.
 */
sealed interface Step {

    /** One thing a virtual user does, named so a report has a row that is not a URL. */
    data class Exec(val name: String, val action: Action) : Step

    /**
     * A step that departs and does not wait. The publish is timed here; the
     * answer is matched at the sink the simulation names, under a step of its
     * own, so a slow producer and a slow pipeline are never added together.
     */
    data class Emit(val name: String, val action: Action, val correlation: Correlation) : Step

    /** [steps], [times] over. */
    data class Repeat(val times: Int, val steps: List<Step>) : Step

    /**
     * [steps], again from the top for as long as [duration] has left on the
     * clock the user started the loop by.
     *
     * The window is the journey's rather than the run's, so a user still inside
     * one when the profile's window closes extends the run. Cutting it off
     * there is the alternative, and that records a failure the target did not
     * cause.
     */
    data class During(val duration: Duration, val steps: List<Step>) : Step {

        init {
            require(duration >= Duration.ZERO) { "a loop cannot run backwards, but was given $duration" }
        }
    }

    /**
     * [steps], for a user whose session satisfies [predicate]. The predicate
     * reads the session and nothing else, so what a run can do stays decided by
     * the value rather than by what a target answered.
     */
    data class When(val predicate: (Session) -> Boolean, val steps: List<Step>) : Step

    /**
     * Think time: the user is doing something that is not this system, so the
     * wait is a gap between steps rather than a step with a timing.
     *
     * It carries no measurement and no name, because a name here is what a
     * report writes a row under and every number under one is what the target
     * took. A pause given a row would be a percentile nobody waited on the
     * target for — diluting the tail with time it did not cause, or, read as
     * latency, inventing seconds of slowness out of a user reading a page.
     */
    data class Pause(val duration: Duration) : Step {

        init {
            require(duration >= Duration.ZERO) { "a pause cannot run backwards, but was $duration" }
        }
    }
}

/**
 * What a virtual user does, in order. A value: nothing here runs, so a
 * scenario can be built, inspected, split across files and compared before
 * anything is sent.
 */
data class Scenario(val name: String, val steps: List<Step>)

/**
 * Every name the tree declares, in the order a user meets them and each of them
 * once however many times it runs.
 *
 * A container holds steps rather than choosing them, so this is readable off
 * the value before anything departs: `plan()` reports the steps a run can take,
 * and a run cannot record under a name that is not here.
 */
val Scenario.stepNames: List<String> get() = steps.flatMap { it.names() }

private fun Step.names(): List<String> = when (this) {
    is Step.Exec -> listOf(name)
    is Step.Emit -> listOf(name)
    is Step.Repeat -> steps.flatMap { it.names() }
    is Step.During -> steps.flatMap { it.names() }
    is Step.When -> steps.flatMap { it.names() }
    is Step.Pause -> emptyList()
}

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

    /**
     * [block], [times] over, under the names it declares once. Shadows
     * `kotlin.repeat`, which would build the body [times] over instead and give
     * the report a row per copy.
     */
    fun repeat(times: Int, block: ScenarioBuilder.() -> Unit) {
        require(times > 0) { "a loop runs at least once, but was asked for $times" }
        steps += Step.Repeat(times, nested(block))
    }

    /** [block], again from the top for as long as [duration] has left when an iteration is due to start. */
    fun during(duration: Duration, block: ScenarioBuilder.() -> Unit) {
        steps += Step.During(duration, nested(block))
    }

    fun emit(name: String, action: Action, keyedBy: Correlation) {
        steps += Step.Emit(name, action, keyedBy)
    }

    fun emit(name: StepName, action: Action, keyedBy: Correlation) {
        steps += Step.Emit(name.name, action, keyedBy)
    }

    // Built through a builder of its own, so the body of a loop is frozen the
    // way the scenario around it is and a name inside it is declared once.
    private fun nested(block: ScenarioBuilder.() -> Unit): List<Step> =
        ScenarioBuilder(name).apply(block).build().steps

    // Frozen rather than copied: a copy is still an ArrayList to a Java caller
    // holding the List, and a scenario that can be added to after it is built
    // is not the value the rest of this design assumes.
    internal fun build(): Scenario = Scenario(name, Collections.unmodifiableList(steps.toList()))
}

fun scenario(name: String, block: ScenarioBuilder.() -> Unit): Scenario =
    ScenarioBuilder(name).apply(block).build()
