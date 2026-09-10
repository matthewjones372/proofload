package io.github.matthewjones372.proofload.scala

import io.github.matthewjones372.proofload.Action
import io.github.matthewjones372.proofload.Scenario
import io.github.matthewjones372.proofload.SessionKey
import io.github.matthewjones372.proofload.StepName
import io.github.matthewjones372.proofload.java.Scenarios
import io.github.matthewjones372.proofload.java.SessionKeys
import io.github.matthewjones372.proofload.java.Steps
import java.time.Duration as JavaDuration
import _root_.scala.concurrent.duration.FiniteDuration
import _root_.scala.reflect.ClassTag

/**
 * One entry in a scenario, as what it does to the Java builder: the builder
 * stays the one place steps are frozen, so a scenario written here cannot
 * become a second way of describing one.
 */
opaque type ScenarioStep = Scenarios.Builder => Scenarios.Builder

/** A step's name, declared once and shared by the scenario and every reading of the result. */
def step(name: String): StepName = Steps.named(name)

/**
 * A typed session key. `ClassTag` recovers at compile time the type Java has to
 * be handed at runtime, so the Scala call reads like the Kotlin one.
 */
def sessionKey[T](name: String)(using tag: ClassTag[T]): SessionKey[T] =
  SessionKeys.of(tag.runtimeClass.asInstanceOf[Class[T]], name)

def exec(step: StepName, action: Action): ScenarioStep = _.exec(step, action)

/** A wait between steps, which records nothing and so has no name. */
def pause(over: FiniteDuration): ScenarioStep = _.pause(asJava(over))

/** The same, in the duration a ZIO caller already holds. */
def pause(over: JavaDuration): ScenarioStep = _.pause(over)

/** What a virtual user does, in order. A vararg rather than a builder: Scala has no lambda-with-receiver to work around. */
def scenario(name: String)(steps: ScenarioStep*): Scenario =
  steps.foldLeft(Scenarios.named(name))((builder, entry) => entry(builder)).build()
