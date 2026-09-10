package io.github.matthewjones372.proofload.ziotest

import io.github.matthewjones372.proofload.RunResultKt
import io.github.matthewjones372.proofload.java.Actions
import io.github.matthewjones372.proofload.scala.at
import io.github.matthewjones372.proofload.scala.apply
import io.github.matthewjones372.proofload.scala.exec
import io.github.matthewjones372.proofload.scala.expecting
import io.github.matthewjones372.proofload.scala.failureRate
import io.github.matthewjones372.proofload.scala.percent
import io.github.matthewjones372.proofload.scala.perSecond
import io.github.matthewjones372.proofload.scala.scenario
import io.github.matthewjones372.proofload.scala.step
import zio.*
import zio.test.ZIOSpecDefault
import zio.test.assert
import zio.test.assertTrue

/**
 * The assertions compose, which is the whole of what `metItsGoals` cannot do:
 * it answers about a run and is a dead end, where these can be negated, joined
 * with `&&` and reported half by half.
 */
object AssertionsSpec extends ZIOSpecDefault:

  private val serve = step("serve")

  private val idle = scenario("idle")(exec(serve, Actions.of(_ => ())))

  private val sample = idle.at(20.perSecond, over = 200.millis).expecting(failureRate under 1.percent)

  def spec = suite("goals as assertions")(
    test("several of them join with &&"):
      for result <- proofload.run(sample)
      yield assert(result)(failedNone && metEveryGoal)
    ,
    test("one that cannot hold is negatable, and the other half still holds"):
      for result <- proofload.run(sample)
      yield assert(result)(!p99Under(serve, 1.nanos) && failedNone)
    ,
    test("a percentile assertion is core's own goal, judged"):
      for result <- proofload.run(sample)
      yield assertTrue(
        p99Under(serve, 1.minute).test(result),
        !p99Under(serve, 1.nanos).test(result),
        failureRateUnder(1.percent).test(result),
        // Whether this machine kept the schedule for a run this small is the
        // machine's business; that the assertion answers what core answers is
        // this module's.
        keptSchedule.test(result) == !RunResultKt.fellBehind(result),
      ),
  )
