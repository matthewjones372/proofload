package io.github.matthewjones372.proofload.ziotest

import io.github.matthewjones372.proofload.Clock
import io.github.matthewjones372.proofload.Goal
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.StepName
import io.github.matthewjones372.proofload.java.Results
import io.github.matthewjones372.proofload.scala.failureRate
import io.github.matthewjones372.proofload.scala.p50
import io.github.matthewjones372.proofload.scala.p95
import io.github.matthewjones372.proofload.scala.p99
import io.github.matthewjones372.proofload.Share
import java.time.Duration as JavaDuration
import zio.test.Assertion
import _root_.scala.concurrent.duration.FiniteDuration
import _root_.scala.jdk.CollectionConverters.ListHasAsScala

/**
 * The goals as `Assertion` values, so they compose.
 *
 * `metItsGoals` answers about a whole run and cannot be negated, combined with
 * `&&` or reported through zio-test's own diffing. These can: what a caller
 * writes is `assert(result)(failedNone && keptSchedule)`, and what fails names
 * the half that did.
 *
 * Each is core's own goal, judged. Nothing here recomputes a number or invents
 * a description: an assertion that disagreed with the report about the same run
 * would be worse than no assertion.
 */
def meeting(goal: Goal): Assertion[RunResult] =
  Assertion.assertion(goal.getDescribed)(result => goal.judge(result).getMet)

def p50Under(step: StepName, limit: FiniteDuration, of: Clock = Clock.ResponseTime): Assertion[RunResult] =
  meeting(p50(step, of) under limit)

def p95Under(step: StepName, limit: FiniteDuration, of: Clock = Clock.ResponseTime): Assertion[RunResult] =
  meeting(p95(step, of) under limit)

def p99Under(step: StepName, limit: FiniteDuration, of: Clock = Clock.ResponseTime): Assertion[RunResult] =
  meeting(p99(step, of) under limit)

/** The same, in the duration a ZIO caller already holds. */
def p50Under(step: StepName, limit: JavaDuration): Assertion[RunResult] = meeting(p50(step) under limit)

def p95Under(step: StepName, limit: JavaDuration): Assertion[RunResult] = meeting(p95(step) under limit)

def p99Under(step: StepName, limit: JavaDuration): Assertion[RunResult] = meeting(p99(step) under limit)

/** The share of the run that may fail. */
def failureRateUnder(share: Share): Assertion[RunResult] = meeting(failureRate under share)

/** Nothing failed at all, which is a count rather than a share and so is not a goal. */
def failedNone: Assertion[RunResult] =
  Assertion.assertion("nothing failed")(result => result.getFailed == 0L)

/**
 * The generator kept the schedule it promised, so the numbers under it describe
 * the target rather than this machine.
 *
 * An assertion for now. Where a run that lost its schedule stops being a result
 * to judge and becomes a run nobody should read, this is a validity gate rather
 * than a goal and should be deleted rather than kept beside one.
 */
def keptSchedule: Assertion[RunResult] = meeting(Goal.KeptSchedule.INSTANCE)

/** Every goal the run itself declared, as one assertion beside `metItsGoals`. */
def metEveryGoal: Assertion[RunResult] =
  Assertion.assertion("every goal the run declared")(result => Results.verdicts(result).asScala.forall(_.getMet))
