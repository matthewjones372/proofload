package io.github.matthewjones372.kestrel.scala

import java.time.Duration as JavaDuration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import _root_.scala.concurrent.duration.DurationInt
import _root_.scala.concurrent.duration.FiniteDuration

class DurationsTest:

  @Test
  def `a duration crosses the boundary and comes back the length it was`(): Unit =
    assertEquals(JavaDuration.ofMillis(250), asJava(250.millis))
    assertEquals(250.millis, asScala(JavaDuration.ofMillis(250)))

  @Test
  def `a minute written the Scala way is a minute on the other side`(): Unit =
    assertEquals(JavaDuration.ofMinutes(1), asJava(1.minute))

  @Test
  def `the conversion is given, so a signature taking either takes what Scala writes`(): Unit =
    val intoJava = summon[Conversion[FiniteDuration, JavaDuration]]
    val outOfJava = summon[Conversion[JavaDuration, FiniteDuration]]

    assertEquals(JavaDuration.ofSeconds(30), intoJava(30.seconds))
    assertEquals(30.seconds, outOfJava(JavaDuration.ofSeconds(30)))
