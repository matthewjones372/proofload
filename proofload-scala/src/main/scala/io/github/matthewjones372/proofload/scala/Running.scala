package io.github.matthewjones372.proofload.scala

import io.github.matthewjones372.proofload.Rate
import io.github.matthewjones372.proofload.Scenario
import io.github.matthewjones372.proofload.Simulation
import io.github.matthewjones372.proofload.http.Http
import io.github.matthewjones372.proofload.java.Https
import io.github.matthewjones372.proofload.java.Simulations
import io.github.matthewjones372.proofload.engine.Proofload as Engine
import io.github.matthewjones372.proofload.java.Proofload as Runner
import java.time.Duration as JavaDuration
import _root_.scala.concurrent.duration.FiniteDuration

/** HTTP steps, under the name the Kotlin DSL gives them. */
object http:

  def baseUrl(url: String): Http = Https.baseUrl(url)

/** What sends a simulation. `Proofload().run(simulation)` is the whole of it. */
object Proofload:

  def apply(): Engine = Runner.create()

extension (scenario: Scenario)

  /** The rate the scenario is sent at and the window it is sent over: a run, as one value. */
  def at(rate: Rate, over: FiniteDuration): Simulation = Simulations.at(scenario, rate, asJava(over))

  /** The same, in the duration a ZIO caller already holds: `zio.Duration` is `java.time.Duration`. */
  def at(rate: Rate, over: JavaDuration): Simulation = Simulations.at(scenario, rate, over)
