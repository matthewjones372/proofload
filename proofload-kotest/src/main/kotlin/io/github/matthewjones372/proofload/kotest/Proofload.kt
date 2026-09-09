package io.github.matthewjones372.proofload.kotest

import io.github.matthewjones372.proofload.Engine
import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.engine.exclusive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The runner for the test this is called from.
 *
 * A Kotest test body is a coroutine, so the runner rides in the coroutine
 * context rather than in a thread local — a test that switches dispatchers
 * would lose a thread local halfway through and start recording into nothing.
 *
 * Called outside [ProofloadExtension] it still works, handing back a runner of
 * its own: a spec should not have to register anything to run a simulation.
 *
 * Silent either way: a Kotest report is somebody else's output, and a run that
 * prints a line every five seconds into it is noise a reader has to scroll past
 * to reach the failure.
 */
suspend fun proofload(): Proofload = coroutineContext[Running]?.proofload ?: Proofload(Progress.silent)

/**
 * A runner for the test this is called from that sends through [engine], as
 * silent as the one that takes no argument.
 *
 * An overload rather than a context element or a spec-level extension:
 * reading an element back is stdlib, but installing one is `withContext` from
 * kotlinx-coroutines, and registering an extension is Kotest, neither of which
 * this module carries at runtime. An engine is a value a spec can hold in a
 * `val` and hand to each test that wants it, which is what the other two would
 * have arranged more slowly.
 *
 * The machine is taken for a run the same way the default takes it, so two
 * specs running at once measure the machine one after the other rather than
 * measuring each other.
 */
fun proofload(engine: Engine): Proofload = Proofload(engine.exclusive(), Progress.silent)

internal class Running(val proofload: Proofload) : AbstractCoroutineContextElement(Running) {
    companion object Key : CoroutineContext.Key<Running>
}
