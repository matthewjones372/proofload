package io.github.matthewjones372.proofload.scala

import io.github.matthewjones372.proofload.Capacity
import io.github.matthewjones372.proofload.Comparison
import io.github.matthewjones372.proofload.Difference
import io.github.matthewjones372.proofload.Floor
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.report.CapacityPageKt
import io.github.matthewjones372.proofload.report.HtmlReportKt
import io.github.matthewjones372.proofload.report.MarkdownKt
import io.github.matthewjones372.proofload.report.PagesKt
import io.github.matthewjones372.proofload.report.StepSummary
import io.github.matthewjones372.proofload.report.StepSummaryKt
import java.nio.file.Path
import _root_.scala.jdk.CollectionConverters.SeqHasAsJava

/**
 * What a run leaves behind, under the names Kotlin gives them.
 *
 * Kotlin's default arguments do not cross the boundary, so the calls these
 * cover are the ones a Scala caller was writing as a file class and a row of
 * hand-placed nulls. Defaults here are Scala's own, and the environment reader
 * a `String => Option[String]` rather than a `kotlin.jvm.functions.Function1`.
 *
 * Both report modules are `compileOnly` on this one: calling any of these needs
 * the module it belongs to on the caller's own classpath, which is the same
 * thing as being able to name what it returns.
 */
extension (result: RunResult)

  /** The run as one self-contained page: the data, the stylesheet and the script in the same file. */
  def toHtmlReport(
    comparison: Option[Comparison] = None,
    floor: Option[Floor] = None,
    differences: Seq[Difference] = Nil,
  ): String = HtmlReportKt.toHtmlReport(result, comparison.orNull, floor.orNull, differences.toList.asJava)

  /** The same page, written to `path` and creating the directories above it. */
  def writeHtmlReport(
    path: Path,
    comparison: Option[Comparison] = None,
    floor: Option[Floor] = None,
    differences: Seq[Difference] = Nil,
  ): Path = HtmlReportKt.writeHtmlReport(result, path, comparison.orNull, floor.orNull, differences.toList.asJava)

  /** What the run measured, as a table GitHub renders and a terminal still reads. */
  def markdown: String = MarkdownKt.markdown(result, null, null)

  /** The same table, against a baseline and the machine floor it was measured on. */
  def markdown(comparison: Option[Comparison], floor: Option[Floor]): String =
    MarkdownKt.markdown(result, comparison.orNull, floor.orNull)

  /**
   * Appends [[markdown]] to the job summary GitHub Actions names in
   * `GITHUB_STEP_SUMMARY`, and answers `NotOnActions` off Actions rather than
   * throwing: the same call runs on a laptop.
   */
  def appendToStepSummary(
    comparison: Option[Comparison] = None,
    floor: Option[Floor] = None,
    environment: String => Option[String] = name => Option(java.lang.System.getenv(name)),
  ): StepSummary = StepSummaryKt.appendToStepSummary(result, comparison.orNull, floor.orNull, reading(environment))

extension (capacity: Capacity)

  /** The curve as a page, with the operating point marked. */
  def writeHtmlReport(path: Path): Path = CapacityPageKt.writeHtmlReport(capacity, path)

/** An `index.html` listing every report in `directory`, newest first. */
def writePagesIndex(directory: Path): Path = PagesKt.writePagesIndex(directory)

/**
 * The environment reader as Kotlin declared it. Written out rather than left to
 * a lambda, so the variance on Kotlin's `Function1` cannot decide whether this
 * compiles.
 */
private def reading(environment: String => Option[String]): kotlin.jvm.functions.Function1[String, String] =
  new kotlin.jvm.functions.Function1[String, String]:
    override def invoke(name: String): String = environment(name).orNull
