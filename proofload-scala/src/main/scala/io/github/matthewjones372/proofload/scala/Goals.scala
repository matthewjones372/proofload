package io.github.matthewjones372.proofload.scala

import io.github.matthewjones372.proofload.Clock
import io.github.matthewjones372.proofload.Goal
import io.github.matthewjones372.proofload.Share
import io.github.matthewjones372.proofload.StepName
import io.github.matthewjones372.proofload.java.Goals
import io.github.matthewjones372.proofload.java.Shares
import java.time.Duration as JavaDuration
import _root_.scala.concurrent.duration.FiniteDuration

/**
 * What a run is asked to achieve, so the report says which goal missed and by
 * how much rather than a test failing on the first assertion that did.
 *
 * Kotlin writes `p99(placeOrder) under 200.milliseconds`. The infix form has no
 * Java spelling, so `Goals` is a row of statics there; Scala has infix
 * extensions, and reading like the Kotlin is the whole point of these.
 *
 * The clock starts at response time, as it does in both other languages: a goal
 * written against service time can be met by a generator that never sent the
 * load.
 */
final class PercentileOf private[scala] (step: StepName, clock: Clock, goal: (StepName, JavaDuration, Clock) => Goal):

  /** The limit the percentile has to stay under. */
  infix def under(limit: FiniteDuration): Goal = goal(step, asJava(limit), clock)

  /** The same, for a caller already holding a `java.time.Duration`, which is what a ZIO one holds. */
  infix def under(limit: JavaDuration): Goal = goal(step, limit, clock)

/** How much of the run may fail, waiting for the share it has to stay under. */
final class FailuresOf private[scala] (goal: Double => Goal):

  infix def under(share: Share): Goal = goal(share.getPercent)

/** A step's good requests, waiting for the share of them a run has to reach. */
final class GoodputOf private[scala] (step: StepName, under: JavaDuration, clock: Clock):

  infix def atLeast(share: Share): Goal = Goals.goodputAtLeast(step, under, share.getPercent, clock)

def p50(step: StepName, of: Clock = Clock.ResponseTime): PercentileOf = PercentileOf(step, of, Goals.p50Under(_, _, _))

def p95(step: StepName, of: Clock = Clock.ResponseTime): PercentileOf = PercentileOf(step, of, Goals.p95Under(_, _, _))

def p99(step: StepName, of: Clock = Clock.ResponseTime): PercentileOf = PercentileOf(step, of, Goals.p99Under(_, _, _))

/** The tail, which a step under a thousand samples misses for want of having measured it. */
def p999(step: StepName, of: Clock = Clock.ResponseTime): PercentileOf =
  PercentileOf(step, of, Goals.p999Under(_, _, _))

/** The share of the whole run that may fail. */
def failureRate: FailuresOf = FailuresOf(percent => Goals.failureRateUnder(percent))

/** The same, asked of one step rather than of the run. */
def failureRate(step: StepName): FailuresOf = FailuresOf(percent => Goals.failureRateUnder(step, percent))

/** The share of a step's requests that were both fast enough and successful. */
def goodput(step: StepName, under: FiniteDuration, of: Clock = Clock.ResponseTime): GoodputOf =
  GoodputOf(step, asJava(under), of)

/**
 * The same, in the duration a ZIO caller already holds. Written as two arities
 * rather than one with a default, because Scala allows only one overloaded
 * alternative to declare defaults and the `FiniteDuration` one has them.
 */
def goodput(step: StepName, under: JavaDuration): GoodputOf = GoodputOf(step, under, Clock.ResponseTime)

def goodput(step: StepName, under: JavaDuration, of: Clock): GoodputOf = GoodputOf(step, under, of)

extension (share: Int)

  /** A share as a percentage: `1.percent` is one in a hundred. */
  def percent: Share = Shares.percent(share.toDouble)

extension (share: Double)

  def percent: Share = Shares.percent(share)
