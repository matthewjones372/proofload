package io.github.matthewjones372.proofload.scala

import io.github.matthewjones372.proofload.Rung
import io.github.matthewjones372.proofload.java.Actions
import io.github.matthewjones372.proofload.java.Goals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import _root_.scala.concurrent.duration.DurationInt

/**
 * Two million a second is a rate no injector offers, so the first rung is void
 * and the search stops on it. A search that climbed would be a run of this
 * build's machine rather than of these readers.
 */
class SearchesTest:

  private val serve = step("serve")

  private val target = scenario("target")(exec(serve, Actions.of(_ => ())))

  private def beyondTheInjector =
    target.sustainable(upTo = 2_000_000.perSecond, holding = 10.millis, Goals.failureRateUnder(1))

  @Test
  def `a search climbs ten rungs up to the ceiling it was given`(): Unit =
    val search = beyondTheInjector

    assertEquals(10, search.getRungs.size)
    assertEquals(200_000.0, search.getRungs.get(0).getPerSecond)
    assertEquals(2_000_000.0, search.getRungs.get(9).getPerSecond)

  @Test
  def `a warm-up is asked for in the durations Scala writes`(): Unit =
    assertEquals(null, beyondTheInjector.getWarmUp)
    assertTrue(beyondTheInjector.warmingUp(20.millis).getWarmUp != null)

  @Test
  def `a Scala caller runs a search and reads the curve it left`(): Unit =
    val capacity = Proofload().run(beyondTheInjector)

    val rung = capacity.curve.head
    assertEquals(200_000.0, rung.rate.getPerSecond)
    assertTrue(
      rung.offered.getPerSecond < rung.rate.getPerSecond,
      "a void rung is one the injector never offered at the rate it asked for",
    )
    assertEquals(Rung.Outcome.Void, rung.outcome)
    assertTrue(rung.result.getCount > 0)

  @Test
  def `a rate no injector offered is not a rate the target sustained`(): Unit =
    val capacity = Proofload().run(beyondTheInjector)

    assertTrue(capacity.voided)
    assertEquals(None, capacity.rate)
    assertEquals(None, capacity.limitedBy)
