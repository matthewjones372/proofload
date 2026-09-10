package io.github.matthewjones372.proofload.scala

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import scala.jdk.StreamConverters.*

/**
 * The floor a consumer's compiler has to clear, read off the bytes they will
 * actually trip over.
 *
 * TASTy is forward-incompatible: a 3.3 compiler cannot read what a 3.9 one
 * wrote, and no dependency override helps because the version is inside the
 * library. 0.1.0-rc3 shipped 28.9 against a page promising the LTS line, and
 * nothing in the build noticed, because compiling `examples-scala` with the
 * same toolchain is green whatever that toolchain is.
 *
 * The floor is written here rather than derived from `scalaVersion`, which
 * would make this test agree with any bump. Raising it is meant to be an edit
 * somebody makes on purpose, having thought about who can still read the jar.
 */
class TastyVersionTest:

  private val major = 28

  private val minor = 3

  private def tastyFiles(): List[Path] =
    val raw = System.getProperty("proofload.scala.classes")
    assertNotNull(raw, "the build must pass -Dproofload.scala.classes; see build.gradle.kts")
    val root = Path.of(raw)
    assertTrue(Files.isDirectory(root), s"no compiled output at $root")
    Files.walk(root).toScala(List).filter(_.getFileName.toString.endsWith(".tasty"))

  private def versionOf(file: Path): (Int, Int) =
    val header = Files.readAllBytes(file).take(6)
    assertEquals(0x5ca1ab1fL, java.lang.Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(header).getInt), s"$file is not TASTy")
    (header(4) & 0x7f, header(5) & 0x7f)

  @Test
  def `every compiled class is readable by a compiler on the supported line`(): Unit =
    val files = tastyFiles()
    assertTrue(files.nonEmpty, "no .tasty files were compiled, so this test proves nothing")

    val tooNew = files.map(file => file -> versionOf(file)).filter { case (_, (got, _)) => got > major }
      ++ files.map(file => file -> versionOf(file)).filter { case (_, (got, low)) => got == major && low > minor }

    assertTrue(
      tooNew.isEmpty,
      s"proofload-scala promises TASTy $major.$minor or lower, so a consumer on that line can read it. " +
        s"These are newer, which means scalaVersion moved off the supported line: " +
        tooNew.map { case (file, (hi, lo)) => s"${file.getFileName} is $hi.$lo" }.mkString(", "),
    )
