package io.github.matthewjones372.proofload.scala

import io.github.matthewjones372.proofload.Action
import io.github.matthewjones372.proofload.ScenarioBuilder
import io.github.matthewjones372.proofload.ScenarioKt
import io.github.matthewjones372.proofload.java.Scenarios
import java.time.Duration as JavaDuration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import _root_.scala.concurrent.duration.DurationInt

/**
 * The Scala layer builds core's own value or it is a second source of truth,
 * and the only way to say so is to build the same scenario three ways and
 * compare.
 */
class ScenarioTest:

  private val browse = step("browse")

  private val noop: Action = _ => ()

  @Test
  def `the Scala DSL produces the scenario the Kotlin DSL produces`(): Unit =
    val fromKotlin = ScenarioKt.scenario(
      "checkout",
      (builder: ScenarioBuilder) =>
        builder.exec("browse", noop)
        _root_.kotlin.Unit.INSTANCE,
    )

    assertEquals(fromKotlin, scenario("checkout")(exec(browse, noop)))

  @Test
  def `the Scala DSL produces the scenario the Java builder produces`(): Unit =
    val fromJava = Scenarios
      .named("checkout")
      .exec(browse, noop)
      .pause(JavaDuration.ofSeconds(1))
      .build()

    assertEquals(fromJava, scenario("checkout")(exec(browse, noop), pause(1.second)))

  @Test
  def `a step's name is the name it was given`(): Unit =
    assertEquals("place order", step("place order").getName)
