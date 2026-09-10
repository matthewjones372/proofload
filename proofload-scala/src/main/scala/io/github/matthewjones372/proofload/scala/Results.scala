package io.github.matthewjones372.proofload.scala

import io.github.matthewjones372.proofload.Clock
import io.github.matthewjones372.proofload.LimitsKt
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.RunResultKt
import io.github.matthewjones372.proofload.StepName
import io.github.matthewjones372.proofload.java.Results
import _root_.scala.concurrent.duration.FiniteDuration

/**
 * What one step measured, on one clock, read off core's own `RunResult`
 * through the Java facade. It holds no number of its own and computes none.
 *
 * The clock starts at response time, as it does in Kotlin: a goal written
 * against service time can be met by a generator that never sent the load.
 */
final class Measured private[scala] (result: RunResult, step: StepName, clock: Clock):

  def responseTime: Measured = Measured(result, step, Clock.ResponseTime)

  def serviceTime: Measured = Measured(result, step, Clock.ServiceTime)

  def ran: Boolean = Results.ran(result, step)

  def count: Long = Results.count(result, step)

  def ok: Long = Results.ok(result, step)

  def failed: Long = Results.failed(result, step)

  def p50: FiniteDuration = asScala(Results.p50(result, step, clock))

  def p95: FiniteDuration = asScala(Results.p95(result, step, clock))

  def p99: FiniteDuration = asScala(Results.p99(result, step, clock))

  def max: FiniteDuration = asScala(Results.max(result, step, clock))

extension (result: RunResult)

  def apply(step: StepName): Measured = Measured(result, step, Clock.ResponseTime)

  /** Whether the generator's own lateness is a material part of the tail this run reports. */
  def fellBehind: Boolean = RunResultKt.fellBehind(result)

  /** Whether it lost the schedule outright, which makes the rate on the page one nobody offered. */
  def lostGround: Boolean = RunResultKt.lostGround(result)

  /** Whether this process ran out of its own room, in which case the run measured the injector. */
  def ranOutOfRoom: Boolean = LimitsKt.ranOutOfRoom(result)
