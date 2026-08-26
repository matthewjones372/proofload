package io.github.matthewjones372.kestrel

import java.util.Collections

/** One thing a virtual user does, named so a report has a row that is not a URL. */
sealed interface Step {

    val name: String

    data class Exec(override val name: String, val action: Action) : Step
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

    // Frozen rather than copied: a copy is still an ArrayList to a Java caller
    // holding the List, and a scenario that can be added to after it is built
    // is not the value the rest of this design assumes.
    internal fun build(): Scenario = Scenario(name, Collections.unmodifiableList(steps.toList()))
}

fun scenario(name: String, block: ScenarioBuilder.() -> Unit): Scenario =
    ScenarioBuilder(name).apply(block).build()
