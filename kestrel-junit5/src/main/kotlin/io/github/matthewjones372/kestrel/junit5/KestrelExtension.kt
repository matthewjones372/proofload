package io.github.matthewjones372.kestrel.junit5

import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.engine.Kestrel
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler

/**
 * Hands a test a [Kestrel] and, when the test fails, attaches what its runs
 * measured to the failure.
 *
 * It never fails a test on its own — not even for a run that fell behind
 * schedule. A tool that fails a build for a reason the test did not ask about
 * is a tool people switch off.
 */
class KestrelExtension : ParameterResolver, TestExecutionExceptionHandler {

    override fun supportsParameter(parameter: ParameterContext, context: ExtensionContext): Boolean =
        parameter.parameter.type == Kestrel::class.java

    override fun resolveParameter(parameter: ParameterContext, context: ExtensionContext): Kestrel =
        context.kestrel()

    override fun handleTestExecutionException(context: ExtensionContext, throwable: Throwable) {
        // Attached rather than wrapped: an assertion failure carries its
        // expected and actual, and a wrapper throws that away for the sake of
        // a longer message.
        context.kestrel().summary()?.let { throwable.addSuppressed(LoadRunSummary(it)) }
        throw throwable
    }

    /**
     * Silent rather than the default: a JUnit report is somebody else's
     * output, and a run that prints a line every five seconds into it is noise
     * a reader has to scroll past to reach the failure. A `main` keeps the
     * lines, which is where the ten minutes of silence was the problem.
     */
    private fun ExtensionContext.kestrel(): Kestrel = requireNotNull(
        getStore(NAMESPACE).getOrComputeIfAbsent(uniqueId, { Kestrel(Progress.silent) }, Kestrel::class.java),
    )

    private companion object {
        // Keyed on the test's own id, so two tests in a class never share one.
        val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create(KestrelExtension::class.java)
    }
}

/** What the runs in a failing test measured, attached to the failure. */
class LoadRunSummary internal constructor(summary: String) : RuntimeException(summary)
