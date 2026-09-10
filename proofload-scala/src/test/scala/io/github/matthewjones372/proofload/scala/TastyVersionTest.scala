package io.github.matthewjones372.proofload.scala

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import _root_.scala.jdk.StreamConverters.StreamHasToScala

/**
 * The TASTy this module publishes, read out of its own compiled output.
 *
 * This is the number a consumer actually hits. `ScalaVersionTest` beside this
 * checks the version the build *declares*, which catches a deliberate bump and
 * not a toolchain that resolves to something else; the bytes below cannot be
 * wrong about what was emitted.
 *
 * rc3 shipped TASTy 28.9. A consumer on 3.8.4 got
 *
 * {{{
 * Forward incompatible TASTy file has version 28.9,
 * produced by Scala 3.9.0-bin-nonbootstrapped,
 * expected stable TASTy from 28.0 to 28.8
 * }}}
 *
 * and no dependency override could help, because the version is in this
 * module's own bytecode. Spec 0139.
 */
class TastyVersionTest:

  /** `0x5CA1AB1F`, which is where a TASTy file says it is one. */
  private val magic = 0x5ca1ab1f

  private def property(name: String): String =
    val value = System.getProperty(name)
    assertNotNull(value, s"the build must pass -D$name; see build.gradle.kts")
    value

  private def compiled: List[Path] =
    val classes = File(property("proofload.classes")).toPath
    assertTrue(Files.isDirectory(classes), s"$classes is not this module's compiled output")
    val found = Files.walk(classes).toScala(List).filter(_.toString.endsWith(".tasty"))
    assertTrue(found.nonEmpty, s"no .tasty under $classes, so there is nothing to check")
    found

  /**
   * The major and minor at the head of a TASTy file.
   *
   * The header is the magic number then three naturals. A natural is base-128,
   * most significant byte first, and the byte with its high bit set is the
   * last one — so a value under 128 is a single byte and everything here is.
   */
  private def versionOf(file: Path): (Int, Int) =
    val bytes = Files.readAllBytes(file)
    val header = ((bytes(0) & 0xff) << 24) | ((bytes(1) & 0xff) << 16) |
      ((bytes(2) & 0xff) << 8) | (bytes(3) & 0xff)
    assertEquals(magic, header, s"$file does not start with the TASTy magic number")

    var at = 4
    def readNat(): Int =
      var value = 0
      var byte = 0
      while
        byte = bytes(at) & 0xff
        at += 1
        value = (value << 7) | (byte & 0x7f)
        (byte & 0x80) == 0
      do ()
      value

    (readNat(), readNat())

  @Test
  def `every published class carries TASTy the declared floor can read`(): Unit =
    val major = property("proofload.tastyMajor").toInt
    val minor = property("proofload.tastyMinor").toInt

    compiled.foreach { file =>
      val (emittedMajor, emittedMinor) = versionOf(file)

      assertEquals(major, emittedMajor, s"$file emitted TASTy major $emittedMajor")
      assertTrue(
        emittedMinor <= minor,
        s"${file.getFileName} emitted TASTy $emittedMajor.$emittedMinor, above the $major.$minor floor " +
          s"${property("proofload.scalaVersion")} promises. Every consumer on a compiler below that gets " +
          "`Forward incompatible TASTy file`, and no dependency override helps them.",
      )
    }

  @Test
  def `the compiler on the runtime classpath is the declared one`(): Unit =
    // The other half of the same promise, and the one that names the cause in
    // words rather than in a version number: what a consumer resolves.
    val classpath = property("proofload.scala.runtimeClasspath")
    val expected = s"scala3-library_3-${property("proofload.scalaVersion")}.jar"

    assertTrue(
      classpath.split(File.pathSeparator).contains(expected),
      s"the published runtime classpath carries no $expected, only: $classpath",
    )
