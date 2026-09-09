package io.github.matthewjones372.proofload.scala

import io.github.matthewjones372.proofload.java.Rates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RatesTest:

  @Test
  def `a rate per second is the same rate whether it was written whole or not`(): Unit =
    assertEquals(Rates.perSecond(50.0), 50.perSecond)
    assertEquals(Rates.perSecond(0.5), 0.5.perSecond)

  @Test
  def `a rate per minute is a rate per second sixty times smaller`(): Unit =
    assertEquals(Rates.perMinute(60.0), 60.perMinute)
    assertEquals(1.perSecond, 60.perMinute)
    assertEquals(Rates.perMinute(90.0), 90.0.perMinute)
