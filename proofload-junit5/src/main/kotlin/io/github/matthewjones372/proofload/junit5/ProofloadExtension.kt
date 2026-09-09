package io.github.matthewjones372.proofload.junit5

import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.engine.exclusive
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler
import kotlin.jvm.optionals.getOrNull

/**
 * Hands a test a [Proofload] and, when the test fails, attaches what its runs
 * measured to the failure.
 *
 * It never fails a test on its own — not even for a run that fell behind
 * schedule. A tool that fails a build for a reason the test did not ask about
 * is a tool people switch off.
 */
class ProofloadExtension : ParameterResolver, TestExecutionExceptionHandler {

    override fun supportsParameter(parameter: ParameterContext, context: ExtensionContext): Boolean =
        parameter.parameter.type == Proofload::class.java

    override fun resolveParameter(parameter: ParameterContext, context: ExtensionContext): Proofload =
        context.proofload()

    override fun handleTestExecutionException(context: ExtensionContext, throwable: Throwable) {
        // Attached rather than wrapped: an assertion failure carries its
        // expected and actual, and a wrapper throws that away for the sake of
        // a longer message.
        context.proofload().summary()?.let { throwable.addSuppressed(LoadRunSummary(it)) }
        throw throwable
    }

    /**
     * Silent rather than the default: a JUnit report is somebody else's
     * output, and a run that prints a line every five seconds into it is noise
     * a reader has to scroll past to reach the failure. A `main` keeps the
     * lines, which is where the ten minutes of silence was the problem.
     */
    private fun ExtensionContext.proofload(): Proofload = requireNotNull(
        getStore(NAMESPACE).getOrComputeIfAbsent(uniqueId, { runner() }, Proofload::class.java),
    )

    /**
     * The engine the test class named, or the default one.
     *
     * A named engine is given the machine the same way the default is: two
     * tests that start a run at the same moment measure it one after the
     * other, and which engine was named is not a reason to lose that.
     */
    private fun ExtensionContext.runner(): Proofload =
        when (val chosen = (testInstance.getOrNull() as? RunsOn)?.engine) {
            null -> Proofload(Progress.silent)
            else -> Proofload(chosen.exclusive(), Progress.silent)
        }

    private companion object {
        // Keyed on the test's own id, so two tests in a class never share one.
        val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create(ProofloadExtension::class.java)
    }
}

/** What the runs in a failing test measured, attached to the failure. */
class LoadRunSummary internal constructor(summary: String) : RuntimeException(summary)
