package io.github.matthewjones372.proofload.scala

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import _root_.scala.jdk.CollectionConverters.ListHasAsScala

/**
 * A snippet nobody compiles is a snippet that rots, so every Scala line on
 * `docs/from-scala.md` has to be a line of the source set the build compiles.
 */
class FromScalaDocTest:

  private val gates = List(
    "examples-scala/src/main/scala/io/github/matthewjones372/proofload/examples/scala/Checkout.scala",
    "examples-scala/src/test/scala/io/github/matthewjones372/proofload/examples/scala/CheckoutSpec.scala",
  )

  private def repoRoot: File =
    val root = System.getProperty("proofload.repoRoot")
    assertNotNull(root, "the build must pass -Dproofload.repoRoot; see build.gradle.kts")
    File(root)

  private def read(path: String): List[String] =
    Files.readAllLines(repoRoot.toPath.resolve(path)).asScala.toList

  private def snippetLines(): List[String] =
    read("docs/from-scala.md")
      .mkString("\n")
      .split("```scala")
      .drop(1)
      .flatMap(_.takeWhile(_ != '`').linesIterator)
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

  @Test
  def `every Scala line on the page is a line of the source set the build compiles`(): Unit =
    val compiled = gates.flatMap(read).map(_.trim).toSet

    val invented = snippetLines().filterNot(compiled.contains)

    assertTrue(invented.isEmpty, s"docs/from-scala.md has Scala that is in no compiled source: $invented")
