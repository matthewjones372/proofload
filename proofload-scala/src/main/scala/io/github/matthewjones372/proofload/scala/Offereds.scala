package io.github.matthewjones372.proofload.scala

import io.github.matthewjones372.proofload.Offered
import io.github.matthewjones372.proofload.Rate
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.java.Offereds
import _root_.scala.concurrent.duration.FiniteDuration

extension (result: RunResult)

  /** What this run offered, empty where it cannot be said: a closed run, or a result nobody ran. */
  def offered: Option[Offered] = Option(Offereds.of(result))

extension (offered: Offered)

  /** The load the plan asked for, as requests a second. */
  def asked: Rate = Offereds.asked(offered)

  /** The load that actually left, which is what the service times describe. */
  def left: Rate = Offereds.left(offered)

  /** The window the load took to leave, which is longer than the one asked for where it fell behind. */
  def over: FiniteDuration = asScala(Offereds.over(offered))

  /** How much of the asked-for load left, as a share of one. */
  def share: Double = offered.getShare
