package io.github.matthewjones372.kestrel.ziotest

import io.github.matthewjones372.kestrel.Engine
import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.engine.ExclusiveKt
import zio.Task
import zio.ZIO
import io.github.matthewjones372.kestrel.engine.Kestrel as Runner

/**
 * The runner for the test this is called from.
 *
 * Blocking rather than compute, and that is the whole module. The call blocks
 * its thread for the length of the run while the engine sends on virtual
 * threads; on ZIO's compute pool that is a starved runtime, and a starved
 * runtime is a scheduler this tool would then measure and report as the
 * target's latency.
 *
 * Nothing here puts a fiber between the departure clock and the socket: a step
 * body is an `Action` and stays one. What a run measures is the wall clock, so
 * `TestClock` cannot move it and must not.
 *
 * Silent either way: a zio-test report is somebody else's output, and a run
 * that prints a line every five seconds into it is noise a reader has to
 * scroll past to reach the failure.
 */
object kestrel:

  def run(simulation: Simulation): Task[RunResult] =
    ZIO.attemptBlocking(Runner(Progress.Companion.getSilent).run(simulation))

  /**
   * The same run, sent by [[on]] rather than by the default engine.
   *
   * The machine is taken for the run the way the default takes it, so two
   * tests under `TestAspect.parallel` measure it one after the other rather
   * than measuring each other.
   */
  def run(simulation: Simulation, on: Engine): Task[RunResult] =
    val silent = Progress.Companion.getSilent
    ZIO.attemptBlocking(Runner(ExclusiveKt.exclusive(on, silent), silent).run(simulation))
