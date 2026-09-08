package io.github.matthewjones372.kestrel.ziotest

import java.io.File
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test rather than promised in a document.
 *
 * Two claims, and the second is the one this module exists to prove. ZIO is
 * `compileOnly`, so nothing here ships an effect runtime to a project that
 * asked for a load test. And wiring a third framework needed nothing from the
 * first two: if it had, "framework-agnostic" was decoration.
 */
class NoSecondStackTest:

  private val exported =
    List("kestrel-core", "kestrel-engine", "kestrel-http", "kestrel-java", "kestrel-scala")

  private val allowed = exported ++ List("scala3-library_3", "scala-library", "kotlin-stdlib", "annotations-")

  private def classpath(): List[String] =
    val raw = System.getProperty("kestrel.ziotest.runtimeClasspath")
    assertNotNull(raw, "the build must pass -Dkestrel.ziotest.runtimeClasspath; see build.gradle.kts")
    raw.split(File.pathSeparatorChar).toList.filter(_.nonEmpty)

  @Test
  def `the main runtime classpath is the modules it delegates to, the Scala library, and nothing else`(): Unit =
    val unexpected = classpath().filterNot(entry => allowed.exists(entry.startsWith))

    assertTrue(unexpected.isEmpty, s"kestrel-zio-test may depend on $allowed only, but found: $unexpected")

  @Test
  def `no effect runtime reaches a project that only asked for a load test`(): Unit =
    val effects = classpath().filter(entry => entry.startsWith("zio") || entry.startsWith("cats-"))

    assertTrue(effects.isEmpty, s"zio-test is compileOnly, but the runtime classpath carries: $effects")

  @Test
  def `no other framework module is on the classpath`(): Unit =
    val frameworks = classpath().filter(entry => entry.startsWith("kestrel-junit5") || entry.startsWith("kestrel-kotest"))

    assertTrue(frameworks.isEmpty, s"wiring a third framework needed a second one: $frameworks")
