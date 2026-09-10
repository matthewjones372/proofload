package io.github.matthewjones372.proofload.scala

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import _root_.scala.jdk.CollectionConverters.ListHasAsScala

/**
 * The compiler this module is published against, held to the one
 * `docs/from-scala.md` promises.
 *
 * TASTy is forward-compatible and not backward, so the version the build uses
 * is the *oldest* compiler that can read the published module. Moving it
 * forward is a breaking change for everyone below it, and it has already
 * happened once: a grouped dependency bump took the build to 3.9.0 while the
 * page still promised the LTS line, and nothing failed, so an rc went out that
 * almost nobody could compile against. A comment did not prevent that. This
 * does.
 */
class ScalaVersionTest:

  /** Scala 3's long-term-support line. `3.9.x` is a Next release, not this. */
  private val ltsLine = "3.3."

  private def builtAgainst: String =
    val version = System.getProperty("proofload.scalaVersion")
    assertNotNull(version, "the build must pass -Dproofload.scalaVersion; see build.gradle.kts")
    version

  private def page: String =
    val root = System.getProperty("proofload.repoRoot")
    assertNotNull(root, "the build must pass -Dproofload.repoRoot; see build.gradle.kts")
    Files.readAllLines(File(root).toPath.resolve("docs/from-scala.md")).asScala.mkString("\n")

  @Test
  def `the page names the compiler the build is published against`(): Unit =
    val named = """\*\*Scala (\d+\.\d+\.\d+)\*\*""".r.findFirstMatchIn(page).map(_.group(1))

    assertTrue(named.isDefined, "docs/from-scala.md must name the compiler as **Scala x.y.z**")
    assertEquals(
      builtAgainst,
      named.get,
      "docs/from-scala.md promises a compiler the build does not use. A consumer who believes the page " +
        "and runs the older one gets a TASTy error, not a helpful message.",
    )

  @Test
  def `the compiler is the LTS line rather than the newest release`(): Unit =
    assertTrue(
      builtAgainst.startsWith(ltsLine),
      s"proofload-scala must be built on the Scala ${ltsLine}x LTS line, and is on $builtAgainst. " +
        "A published Scala library cannot be read by any compiler older than the one that built it, " +
        "so publishing off a Next release makes the module unusable for most of the ecosystem.",
    )
