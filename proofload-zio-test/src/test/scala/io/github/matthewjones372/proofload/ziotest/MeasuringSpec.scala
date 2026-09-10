package io.github.matthewjones372.proofload.ziotest

import io.github.matthewjones372.proofload.java.Actions
import io.github.matthewjones372.proofload.scala.at
import io.github.matthewjones372.proofload.scala.apply
import io.github.matthewjones372.proofload.scala.exec
import io.github.matthewjones372.proofload.scala.perSecond
import io.github.matthewjones372.proofload.scala.scenario
import io.github.matthewjones372.proofload.scala.step
import java.nio.file.Files
import java.nio.file.Path
import zio.*
import zio.test.TestClock
import zio.test.assertTrue

/**
 * A spec that is only its measurement: no aspects and no report writing of its
 * own. What it asserts is that extending the base class was enough.
 */
object MeasuringSpec extends ProofloadSpec:

  private val serve = step("serve")

  private val idle = scenario("idle")(exec(serve, Actions.of(_ => ())))

  // Under `build/` rather than a temp directory, so what this spec leaves is
  // something a person can open, and something `clean` takes away.
  override val reportsTo: Path = Path.of("build/proofload-measuring")

  def spec = suite("a spec that is already a load test")(
    test("the spec's own clock is the live one, so a schedule in it finishes"):
      for
        clock <- ZIO.clock
        live = !clock.isInstanceOf[TestClock]
        // Asked rather than slept on first: under a `TestClock` this hangs
        // instead of failing, which is the failure mode the base class exists
        // to prevent and a poor one to reproduce in its own test.
        _ <- ZIO.sleep(10.millis).when(live)
      yield assertTrue(live)
    ,
    test("a measured run leaves its page beside its result"):
      for result <- measured("idle")(idle.at(20.perSecond, over = 200.millis))
      yield assertTrue(
        result(serve).count > 0,
        Files.readString(reportsTo.resolve("idle.html")).contains("serve"),
      ),
  )
