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

/**
 * A node of what a virtual user does. [Exec] and [Emit] are the named leaves a
 * report has a row for; [Repeat] and [When] hold steps rather than being one,
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
     * [steps], for a user whose session satisfies [predicate]. The predicate
     * reads the session and nothing else, so what a run can do stays decided by
     * the value rather than by what a target answered.
     */
    data class When(val predicate: (Session) -> Boolean, val steps: List<Step>) : Step
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
    is Step.When -> steps.flatMap { it.names() }
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
