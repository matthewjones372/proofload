package io.github.matthewjones372.kestrel.scala

import java.io.File
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test rather than promised in a document.
 *
 * The Scala library is the one thing here that Java's facade does not carry.
 * An effect runtime arriving beside it is the failure this catches: a second
 * scheduler inside a load generator measures its own queueing and reports it
 * as the target's.
 */
class OnlyTheScalaLibraryTest:

  private val exported = List("kestrel-core", "kestrel-engine", "kestrel-http", "kestrel-java")

  private val allowed = exported ++ List("scala3-library_3", "scala-library", "kotlin-stdlib", "annotations-")

  private def classpath(): List[String] =
    val raw = System.getProperty("kestrel.scala.runtimeClasspath")
    assertNotNull(raw, "the build must pass -Dkestrel.scala.runtimeClasspath; see build.gradle.kts")
    raw.split(File.pathSeparatorChar).toList.filter(_.nonEmpty)

  @Test
  def `the main runtime classpath is the modules it delegates to, the Scala library, and nothing else`(): Unit =
    val unexpected = classpath().filterNot(entry => allowed.exists(entry.startsWith))

    assertTrue(unexpected.isEmpty, s"kestrel-scala may depend on $allowed only, but found: $unexpected")

  @Test
  def `every module it hands out values from is exported`(): Unit =
    exported.foreach: module =>
      assertTrue(
        classpath().exists(_.startsWith(module)),
        s"a Scala caller has to be able to name what $module returns: ${classpath()}",
      )
