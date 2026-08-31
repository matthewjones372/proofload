package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Engine
import io.github.matthewjones372.kestrel.Exclusivity
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.then
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock

/**
 * This engine, sending one simulation at a time on this machine: a run started
 * while another is going waits for it rather than measuring it.
 *
 * A decorator rather than something inside an engine, because exclusivity is
 * the machine's property and not virtual threads': two engines in one process
 * queue for the same machine.
 */
fun Engine.exclusive(): Engine = Exclusive(this)

private class Exclusive(private val engine: Engine) : Engine {

    override fun run(simulation: Simulation): RunResult =
        exclusively(Exclusivity.Running) { engine.run(simulation) }
}

/**
 * Runs [work] with the machine to itself, entering [during] once it is held.
 *
 * The lock is taken before [work] starts, so a wait is never inside the
 * measurement that follows it — a queued run reports the target's latency and
 * not the queue's.
 */
internal fun <T> exclusively(during: Exclusivity, work: () -> T): T {
    // A rung of a capacity search arrives here on the thread that is already
    // holding the machine for the search: the same run continuing, rather than
    // a second one asking, so it neither waits nor gives the machine back early.
    if (machine.isHeldByCurrentThread) return work()

    val asked = if (machine.tryLock()) Exclusivity.Idle else Exclusivity.Waiting(Instant.now())
    // Blocked here rather than in the branch above, so the state saying this
    // run queued is a value that exists before the queueing does.
    if (asked is Exclusivity.Waiting) machine.lock()
    return try {
        asked.then(during)
        work()
    } finally {
        machine.unlock()
    }
}

/**
 * One run at a time in this JVM, whoever asked. A single lock for the whole
 * process rather than a field of each decorator, since what it stands for is
 * the one machine underneath all of them.
 */
private val machine = ReentrantLock()
