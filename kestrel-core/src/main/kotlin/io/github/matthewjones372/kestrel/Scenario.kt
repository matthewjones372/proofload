package io.github.matthewjones372.kestrel

import java.util.Collections

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
