@file:JvmName("HoldsTheMachine")

package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Engine
import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.RunRecorder
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * A process that asks for the machine, says when it asked, when it got inside a
 * run and when it left, and stays inside until the file named by its first
 * argument appears.
 *
 * A `main` because one JVM cannot show that two JVMs serialise:
 * `AcrossProcessesTest` forks this and reads these lines. `asking` is what lets
 * that test know this process is queueing rather than yet to start. A second
 * argument of `silent` asks the run for [Progress.silent].
 */
fun main(args: Array<String>) {
    val release = Path.of(args[0])
    val progress = if (args.getOrNull(1) == "silent") Progress.silent else Progress.lines()
    val kestrel = Kestrel(engine = Holding(release).exclusive(progress))
    say("asking")
    kestrel.run(scenario("holding") { exec("hold") { } }.at(1.perSecond, over = 1.milliseconds))
}

/** Sends nothing: what these processes are for is the queue in front of a run, not a run. */
private class Holding(private val release: Path) : Engine {

    override fun run(simulation: Simulation): RunResult {
        say("in")
        while (!Files.exists(release)) Thread.sleep(POLL_MILLIS)
        say("out")
        return RunRecorder(Instant.now()).freeze()
    }
}

// Flushed by `println` itself, so the parent reads each line as it happens
// rather than when this process exits.
private fun say(what: String) = println("$what ${System.currentTimeMillis()}")

private const val POLL_MILLIS = 20L
