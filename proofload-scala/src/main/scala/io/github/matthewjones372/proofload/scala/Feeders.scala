package io.github.matthewjones372.proofload.scala

import io.github.matthewjones372.proofload.Feeder
import io.github.matthewjones372.proofload.Search
import io.github.matthewjones372.proofload.SessionKey
import io.github.matthewjones372.proofload.Simulation
import io.github.matthewjones372.proofload.java.Feeders
import _root_.scala.jdk.CollectionConverters.SeqHasAsJava

/**
 * Fills `key` with a value worked out from the user's number.
 *
 * The function is a second parameter list so the lambda reads as a block, which
 * is what `feed(key) { user -> ... }` reads like in Kotlin.
 */
def feed[T](key: SessionKey[T])(value: Long => T): Feeder = Feeders.of(key, user => value(user))

/** Fills `key` from `values`, indexed by the user's number and wrapping round at the end. */
def feedFrom[T](key: SessionKey[T], values: Seq[T]): Feeder = Feeders.fromList(key, values.toList.asJava)

extension (feeder: Feeder)

  /** Both, for the same user. Where they fill one key the later one wins. */
  def +(next: Feeder): Feeder = Feeders.combined(feeder, next)

extension (simulation: Simulation)

  /** What each of this run's users starts with. */
  def fedBy(feeder: Feeder): Simulation = Feeders.fedBy(simulation, feeder)

extension (search: Search)

  /** The same, for every rung of a search. */
  def fedBy(feeder: Feeder): Search = Feeders.fedBy(search, feeder)
