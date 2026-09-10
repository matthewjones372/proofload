package io.github.matthewjones372.proofload.scala

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import _root_.scala.jdk.CollectionConverters.IteratorHasAsScala
import _root_.scala.jdk.CollectionConverters.ListHasAsScala

/**
 * `RunResultKt` is how Kotlin happens to compile a file, and `kotlin.jvm
 * .functions.Function1` is how it happens to compile a lambda. A load test
 * written against 0.1.0-rc1 had both in its source, which is this module
 * failing at what it is for rather than a caller writing it badly.
 */
class NoFileClassesTest:

  private val fileClass = """\b[A-Z][A-Za-z0-9_]*Kt\.""".r

  private def repoRoot: File =
    val root = System.getProperty("proofload.repoRoot")
    assertNotNull(root, "the build must pass -Dproofload.repoRoot; see build.gradle.kts")
    File(root)

  private def consumerSources: List[File] =
    Files
      .walk(repoRoot.toPath.resolve("examples-scala/src"))
      .iterator
      .asScala
      .map(_.toFile)
      .filter(_.getName.endsWith(".scala"))
      .toList

  private def lines(file: File): List[String] = Files.readAllLines(file.toPath).asScala.toList

  @Test
  def `no consumer source reaches through a Kotlin file class`(): Unit =
    val leaked = consumerSources.flatMap(file => lines(file).filter(fileClass.findFirstIn(_).isDefined))

    assertTrue(leaked.isEmpty, s"a Scala caller should never name a file class, but found: $leaked")

  @Test
  def `no consumer source imports kotlin jvm`(): Unit =
    val leaked = consumerSources.flatMap(file => lines(file).filter(_.contains("kotlin.jvm")))

    assertTrue(leaked.isEmpty, s"a Scala caller should never name a Kotlin function type, but found: $leaked")
