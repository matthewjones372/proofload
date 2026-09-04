package io.github.matthewjones372.kestrel.java

import io.github.matthewjones372.kestrel.Action
import io.github.matthewjones372.kestrel.Step
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.action
import java.util.function.Consumer

/** A step body, which Kotlin writes as a lambda whose receiver is the scope. */
object Actions {

    @JvmStatic
    fun of(body: Consumer<StepScope>): Action = action { body.accept(this) }
}

/**
 * The steps whose core constructor takes a `kotlin.time.Duration`, which is a
 * value class and so reaches Java as a bare `long` under a mangled name.
 */
internal object Pauses {

    @JvmStatic
    @JvmName("of")
    fun of(duration: java.time.Duration): Step = Step.Pause(duration.asKestrel())
}
