package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Engine
import io.github.matthewjones372.kestrel.Exclusivity
import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.then
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.toKotlinDuration

/**
 * This engine, sending one simulation at a time on this machine: a run started
 * while another is going waits for it rather than measuring it.
 *
 * A decorator rather than something inside an engine, because exclusivity is
 * the machine's property and not virtual threads': two engines in one process
 * queue for the same machine, as do two processes.
 *
 * [progress] is asked for nothing; it says whether this run is one a reader is
 * being told about, so a caller who passed [Progress.silent] is not told about
 * a queue either.
 */
fun Engine.exclusive(progress: Progress = Progress.lines()): Engine = Exclusive(this, progress)

private class Exclusive(private val engine: Engine, private val progress: Progress) : Engine {

    override fun run(simulation: Simulation): RunResult =
        exclusively(Exclusivity.Running, progress) { engine.run(simulation) }
}

/**
 * Runs [work] with the machine to itself, entering [during] once it is held.
 *
 * Two locks, in this order because they answer different questions: a
 * [ReentrantLock] serialises the threads of this JVM and lets a capacity search
 * hold the machine across its rungs, and a file lock under the temp directory
 * serialises this process against every other one on the host. Both are taken
 * before [work] starts, so a wait is never inside the measurement that follows
 * it — a queued run reports the target's latency and not the queue's.
 */
internal fun <T> exclusively(during: Exclusivity, progress: Progress = Progress.silent, work: () -> T): T {
    // A rung of a capacity search arrives here on the thread that is already
    // holding the machine for the search: the same run continuing, rather than
    // a second one asking, so it neither waits nor gives the machine back early.
    if (machine.isHeldByCurrentThread) return work()

    // `-Dkestrel.exclusive=false` is for a caller that means to run several at
    // once — a benchmark measuring what four processes do to each other, which
    // is a measurement of the tool rather than one this can protect.
    if (!exclusivityWanted()) return work()

    val since = Instant.now()
    val free = machine.tryLock()
    // Blocked here rather than in the branch above, so the moment this run
    // started queueing is a value that exists before the queueing does.
    if (!free) machine.lock()
    return try {
        takeTheMachine().use { host ->
            val asked = if (free && host?.waited != true) Exclusivity.Idle else Exclusivity.Waiting(since)
            asked.then(during)
            asked.reportedTo(progress)
            work()
        }
    } finally {
        machine.unlock()
    }
}

/**
 * How long the machine was queued for, told to the reporter the run was given.
 *
 * Asked rather than compared: an earlier draft printed unless the reporter was
 * identically [Progress.silent], which silenced the one instance this
 * repository ships and nobody else's — a caller's own no-op `Progress` would
 * have been talked over. `waited` has a no-op default like every other member
 * added since, so silence is what a reporter does rather than what it is.
 *
 * A run that did not queue says nothing either way.
 */
private fun Exclusivity.reportedTo(progress: Progress) {
    if (this !is Exclusivity.Waiting) return
    progress.waited(Duration.between(since, Instant.now()).toKotlinDuration())
}

private fun exclusivityWanted(): Boolean = System.getProperty("kestrel.exclusive")?.toBooleanStrictOrNull() != false

/**
 * One run at a time in this JVM, whoever asked. A single lock for the whole
 * process rather than a field of each decorator, since what it stands for is
 * the one machine underneath all of them.
 */
private val machine = ReentrantLock()
