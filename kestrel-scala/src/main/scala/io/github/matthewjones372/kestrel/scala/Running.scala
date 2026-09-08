package io.github.matthewjones372.kestrel.scala

import io.github.matthewjones372.kestrel.Rate
import io.github.matthewjones372.kestrel.Scenario
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.http.Http
import io.github.matthewjones372.kestrel.java.Https
import io.github.matthewjones372.kestrel.java.Simulations
import io.github.matthewjones372.kestrel.engine.Kestrel as Engine
import io.github.matthewjones372.kestrel.java.Kestrel as Runner
import _root_.scala.concurrent.duration.FiniteDuration

/** HTTP steps, under the name the Kotlin DSL gives them. */
object http:

  def baseUrl(url: String): Http = Https.baseUrl(url)

/** What sends a simulation. `Kestrel().run(simulation)` is the whole of it. */
object Kestrel:

  def apply(): Engine = Runner.create()

extension (scenario: Scenario)

  /** The rate the scenario is sent at and the window it is sent over: a run, as one value. */
  def at(rate: Rate, over: FiniteDuration): Simulation = Simulations.at(scenario, rate, asJava(over))
