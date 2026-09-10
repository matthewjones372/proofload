package io.github.matthewjones372.proofload.ziotest

import io.github.matthewjones372.proofload.scala.at
import io.github.matthewjones372.proofload.scala.exec
import io.github.matthewjones372.proofload.scala.failureRate
import io.github.matthewjones372.proofload.scala.goodput
import io.github.matthewjones372.proofload.scala.p99
import io.github.matthewjones372.proofload.scala.pause
import io.github.matthewjones372.proofload.scala.percent
import io.github.matthewjones372.proofload.scala.perSecond
import io.github.matthewjones372.proofload.scala.scenario
import io.github.matthewjones372.proofload.scala.step
import io.github.matthewjones372.proofload.scala.sustainable
import io.github.matthewjones372.proofload.scala.warmingUp
import io.github.matthewjones372.proofload.java.Actions
import io.github.matthewjones372.proofload.java.Goals
import io.github.matthewjones372.proofload.java.Rates
import io.github.matthewjones372.proofload.java.Searches
import io.github.matthewjones372.proofload.java.Simulations
import java.time.Duration as JavaDuration
import java.util.List as JavaList
import zio.*
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/**
 * `zio.Duration` is `java.time.Duration`, so this file writes `1.minute` and
 * means ZIO's. It imports `zio.*` and nothing else that spells a duration, and
 * it sets no language flag: both are the point of the test, so adding
 * `DurationInt` or `implicitConversions` here would delete what it checks.
 */
object DurationsSpec extends ZIOSpecDefault:

  private val browse = step("browse")

  private val browsing = scenario("browsing")(exec(browse, Actions.of(_ => ())), pause(1.second))

  def spec = suite("the durations a ZIO caller already holds")(
    test("a run takes the window in zio's own duration"):
      assertTrue(
        browsing.at(20.perSecond, over = 500.millis) ==
          Simulations.at(browsing, Rates.perSecond(20), JavaDuration.ofMillis(500)),
      )
    ,
    test("a search takes its hold and its warm-up in the same one"):
      val search = browsing.sustainable(upTo = 100.perSecond, holding = 1.second).warmingUp(2.seconds)

      assertTrue(
        search == Searches.warmingUp(
          Searches.sustainable(browsing, Rates.perSecond(100), JavaDuration.ofSeconds(1), JavaList.of()),
          JavaDuration.ofSeconds(2),
        ),
      )
    ,
    test("a goal takes its limit in the same one"):
      assertTrue(
        (p99(browse) under 200.millis) == Goals.p99Under(browse, JavaDuration.ofMillis(200)),
        (failureRate under 1.percent) == Goals.failureRateUnder(1.0),
        (goodput(browse, 200.millis) atLeast 99.percent) ==
          Goals.goodputAtLeast(browse, JavaDuration.ofMillis(200), 99.0),
      ),
  )
