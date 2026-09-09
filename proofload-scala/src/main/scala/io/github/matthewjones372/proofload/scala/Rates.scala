package io.github.matthewjones372.proofload.scala

import io.github.matthewjones372.proofload.Rate
import io.github.matthewjones372.proofload.java.Rates

/**
 * How often virtual users arrive. Kotlin writes `50.perSecond`, which is an
 * extension on a value class and so compiles to a name with a hash in it.
 */
extension (rate: Int)

  def perSecond: Rate = Rates.perSecond(rate.toDouble)

  def perMinute: Rate = Rates.perMinute(rate.toDouble)

extension (rate: Double)

  def perSecond: Rate = Rates.perSecond(rate)

  def perMinute: Rate = Rates.perMinute(rate)
