package io.github.matthewjones372.proofload.ziotest

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reads `OutputsSpec` rather than making the claim inside it, which would find
 * the word in its own assertion.
 */
class OutputsAreEffectsTest:

  private val wrapper = "attemptBlocking"

  @Test
  def `a spec writes every output without wrapping one by hand`(): Unit =
    val root = java.lang.System.getProperty("proofload.repoRoot")
    assertNotNull(root, "the build must pass -Dproofload.repoRoot; see build.gradle.kts")
    val spec = Path
      .of(root)
      .resolve("proofload-zio-test/src/test/scala/io/github/matthewjones372/proofload/ziotest/OutputsSpec.scala")

    val wrapped = Files.readAllLines(spec).stream.filter(_.contains(wrapper)).toArray

    assertTrue(wrapped.isEmpty, s"the outputs are effects, so no caller should wrap one: ${wrapped.mkString}")
