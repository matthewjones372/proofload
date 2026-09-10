package io.github.matthewjones372.proofload.scala

import io.github.matthewjones372.proofload.Clock
import io.github.matthewjones372.proofload.java.Goals
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import _root_.scala.concurrent.duration.DurationInt

/**
 * Equality against the Java facade rather than against a description: the
 * infix form is a second spelling of the same goal, and a spelling that builds
 * a different value is a third DSL.
 */
class GoalsTest:

  private val placeOrder = step("place order")

  @Test
  def `a percentile goal is the one the facade builds`(): Unit =
    assertEquals(Goals.p50Under(placeOrder, Duration.ofMillis(200)), p50(placeOrder) under 200.millis)
    assertEquals(Goals.p95Under(placeOrder, Duration.ofMillis(200)), p95(placeOrder) under 200.millis)
    assertEquals(Goals.p99Under(placeOrder, Duration.ofMillis(200)), p99(placeOrder) under 200.millis)
    assertEquals(Goals.p999Under(placeOrder, Duration.ofMillis(200)), p999(placeOrder) under 200.millis)

  @Test
  def `a percentile goal can be asked of the other clock`(): Unit =
    assertEquals(
      Goals.p99Under(placeOrder, Duration.ofMillis(200), Clock.ServiceTime),
      p99(placeOrder, of = Clock.ServiceTime) under 200.millis,
    )

  @Test
  def `a failure rate is asked of the run or of one step`(): Unit =
    assertEquals(Goals.failureRateUnder(1.0), failureRate under 1.percent)
    assertEquals(Goals.failureRateUnder(placeOrder, 0.1), failureRate(placeOrder) under 0.1.percent)

  @Test
  def `a goodput goal carries the window it counts as good`(): Unit =
    assertEquals(
      Goals.goodputAtLeast(placeOrder, Duration.ofMillis(200), 99.0),
      goodput(placeOrder, under = 200.millis) atLeast 99.percent,
    )
    assertEquals(
      Goals.goodputAtLeast(placeOrder, Duration.ofMillis(200), 99.0, Clock.ServiceTime),
      goodput(placeOrder, under = 200.millis, of = Clock.ServiceTime) atLeast 99.percent,
    )
