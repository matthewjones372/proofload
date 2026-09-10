package io.github.matthewjones372.proofload.ziotest

import io.github.matthewjones372.proofload.Capacity
import io.github.matthewjones372.proofload.Engine
import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.Search
import io.github.matthewjones372.proofload.Simulation
import io.github.matthewjones372.proofload.engine.ExclusiveKt
import io.github.matthewjones372.proofload.report.StepSummary
import io.github.matthewjones372.proofload.scala.appendToStepSummary
import io.github.matthewjones372.proofload.scala.markdown
import io.github.matthewjones372.proofload.scala.writeHtmlReport
import io.github.matthewjones372.proofload.scala.writePagesIndex as writeIndex
import java.nio.file.Path
import zio.IO
import zio.UIO
import zio.ZIO
import io.github.matthewjones372.proofload.engine.Proofload as Runner

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
object proofload:

  def run(simulation: Simulation): IO[ProofloadError, RunResult] =
    sent(ZIO.attemptBlocking(Runner(Progress.Companion.getSilent).run(simulation)))

  /**
   * The same run, sent by [[on]] rather than by the default engine.
   *
   * The machine is taken for the run the way the default takes it, so two
   * tests under `TestAspect.parallel` measure it one after the other rather
   * than measuring each other.
   */
  def run(simulation: Simulation, on: Engine): IO[ProofloadError, RunResult] =
    val silent = Progress.Companion.getSilent
    sent(ZIO.attemptBlocking(Runner(ExclusiveKt.exclusive(on, silent), silent).run(simulation)))

  /**
   * The rate the scenario sustains, hunted rung by rung.
   *
   * A search is many runs and holds each rung for its whole window, so the
   * test that calls this needs a timeout written against the search's own
   * `worstCase` rather than against a run's.
   */
  def run(search: Search): IO[ProofloadError, Capacity] =
    sent(ZIO.attemptBlocking(Runner(Progress.Companion.getSilent).run(search)))

  /** The same search, sent by [[on]] rather than by the default engine. */
  def run(search: Search, on: Engine): IO[ProofloadError, Capacity] =
    val silent = Progress.Companion.getSilent
    sent(ZIO.attemptBlocking(Runner(ExclusiveKt.exclusive(on, silent), silent).run(search)))

  private def sent[A](effect: ZIO[Any, Throwable, A]): IO[ProofloadError, A] = effect.mapError(ProofloadError.of)

  /**
   * The run as a page, written on the blocking executor the run itself went
   * out on. A module that owns `attemptBlocking` for the run owns it for the
   * run's outputs too, or a caller has to learn which half of a library is
   * effectful with no rule for telling.
   */
  def writeHtmlReport(result: RunResult, path: Path): IO[ProofloadError, Path] =
    sent(ZIO.attemptBlocking(result.writeHtmlReport(path)))

  /** The curve as a page, with the operating point marked. */
  def writeHtmlReport(capacity: Capacity, path: Path): IO[ProofloadError, Path] =
    sent(ZIO.attemptBlocking(capacity.writeHtmlReport(path)))

  /** Appends the run's table to the job summary, and says `NotOnActions` where there is none. */
  def appendToStepSummary(result: RunResult): IO[ProofloadError, StepSummary] =
    sent(ZIO.attemptBlocking(result.appendToStepSummary()))

  /** The same, reading the variable through [[environment]] rather than through the JVM's own. */
  def appendToStepSummary(result: RunResult, environment: String => Option[String]): IO[ProofloadError, StepSummary] =
    sent(ZIO.attemptBlocking(result.appendToStepSummary(environment = environment)))

  /**
   * The run as a table. No error channel: this reads what is already in hand
   * and writes nothing, so a throw here is a bug in this library rather than
   * something a caller can do anything about.
   */
  def markdown(result: RunResult): UIO[String] = ZIO.succeed(result.markdown)

  /**
   * Run it, write its page under [[into]], and append its table to the job
   * summary.
   *
   * One call because a run whose report is not written is a run nobody can
   * read, and separating them means every caller writes the same `flatMap`. A
   * caller who wants the result without a report still has [[run]].
   *
   * Here as well as on `ProofloadSpec` so a project that cannot change its base
   * class still gets the reporting.
   */
  def measured(name: String, into: Path)(simulation: Simulation): IO[ProofloadError, RunResult] =
    for
      result <- run(simulation)
      _ <- writeHtmlReport(result, into.resolve(s"$name.html"))
      _ <- appendToStepSummary(result)
    yield result

  /** An `index.html` listing every report in [[directory]], newest first. */
  def writePagesIndex(directory: Path): IO[ProofloadError, Path] = sent(ZIO.attemptBlocking(writeIndex(directory)))
