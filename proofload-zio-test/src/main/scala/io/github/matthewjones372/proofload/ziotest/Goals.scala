package io.github.matthewjones372.proofload.ziotest

import io.github.matthewjones372.proofload.RemedyKt
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.Verdict
import io.github.matthewjones372.proofload.java.Results
import zio.test.TestResult
import zio.test.assertCompletes
import zio.test.assertNever
import _root_.scala.jdk.CollectionConverters.ListHasAsScala

/**
 * What the run's own goals concluded, in the words the CLI prints them in
 * rather than in words invented here.
 *
 * Assert when one number decides the test. This is for when several do: the
 * failure names every goal that missed, and the remedy the goal carries, in
 * place of one assertion stopping at the first.
 */
extension (result: RunResult)

  def metItsGoals: TestResult = missedGoals(result) match
    case Nil => assertCompletes
    case missed => assertNever(missed.mkString("\n"))

private[ziotest] def missedGoals(result: RunResult): List[String] =
  Results.verdicts(result).asScala.filterNot(_.getMet).map(described).toList

private def described(verdict: Verdict): String =
  val remedy = Option(RemedyKt.getRemedy(verdict)).fold("")(why => s"\n  $why")
  s"missed  ${verdict.getGoal.getDescribed}$remedy"
