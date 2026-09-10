package io.github.matthewjones372.proofload.scala

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FeedersTest:

  private val personId = sessionKey[String]("personId")

  private val target = sessionKey[String]("target")

  @Test
  def `a feeder gives each user a value worked out from its number`(): Unit =
    val feeder = feed(personId)(user => s"person-$user")

    assertEquals("person-7", feeder.forUser(7).get(personId))

  @Test
  def `a feeder from a sequence wraps round at the end`(): Unit =
    val feeder = feedFrom(personId, Seq("luke", "leia"))

    assertEquals("luke", feeder.forUser(4).get(personId))
    assertEquals("leia", feeder.forUser(5).get(personId))

  @Test
  def `two feeders added together fill both keys for the same user`(): Unit =
    val both = feedFrom(personId, Seq("luke", "leia")) + feed(target)(user => s"target-$user")

    assertEquals("leia", both.forUser(5).get(personId))
    assertEquals("target-5", both.forUser(5).get(target))
