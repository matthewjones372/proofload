package io.github.matthewjones372.proofload.ziotest

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import zio.Unsafe
import zio.ZIO

/**
 * The index the base class writes on its way out, run here rather than watched
 * from inside a spec: it happens after the last test, which is after anything
 * that spec could assert.
 */
class IndexAfterTheLastRunTest:

  private def ran[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit unsafe => zio.Runtime.default.unsafe.run(effect.orDie).getOrThrowFiberFailure())

  @Test
  def `four runs leave one index linking four reports`(): Unit =
    val reports = Files.createTempDirectory("proofload-index")
    List("one", "two", "three", "four").foreach: name =>
      Files.writeString(reports.resolve(s"$name.html"), s"<html><body>$name</body></html>")

    val index = ran(proofload.writePagesIndex(reports))

    val page = Files.readString(index)
    assertEquals(reports.resolve("index.html"), index)
    List("one", "two", "three", "four").foreach: name =>
      assertTrue(page.contains(s"$name.html"), s"the index does not link $name")

  @Test
  def `a spec that measured nothing leaves nothing behind`(): Unit =
    val empty = Files.createTempDirectory("proofload-empty").resolve("never-written")

    assertTrue(!Files.exists(empty))

  @Test
  def `the reports go somewhere a build cleans by default`(): Unit =
    val spec = new ProofloadSpec:
      def spec = suite("nothing")()

    assertEquals(Path.of("target/proofload"), spec.reportsTo)
