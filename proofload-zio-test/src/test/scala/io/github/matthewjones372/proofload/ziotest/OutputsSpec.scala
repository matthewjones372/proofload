package io.github.matthewjones372.proofload.ziotest

import io.github.matthewjones372.proofload.java.Actions
import io.github.matthewjones372.proofload.report.StepSummary
import io.github.matthewjones372.proofload.scala.at
import io.github.matthewjones372.proofload.scala.exec
import io.github.matthewjones372.proofload.scala.perSecond
import io.github.matthewjones372.proofload.scala.scenario
import io.github.matthewjones372.proofload.scala.step
import java.nio.file.Files
import zio.*
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/**
 * Every output a run has, written without a hand-rolled blocking wrapper.
 * A module that owns the blocking executor for the run and not for the run's
 * outputs teaches a caller that some of it is effectful, with no rule for
 * telling which. `OutputsAreEffectsTest` beside this reads this source and
 * fails when a wrapper appears in it.
 */
object OutputsSpec extends ZIOSpecDefault:

  private val serve = step("serve")

  private val idle = scenario("idle")(exec(serve, Actions.of(_ => ())))

  private val reports = Files.createTempDirectory("proofload")

  private val summaryFile = reports.resolve("summary.md")

  def spec = suite("the outputs, as effects")(
    test("a run writes its page, its table, its summary and the index"):
      for
        result <- proofload.run(idle.at(20.perSecond, over = 200.millis))
        page <- proofload.writeHtmlReport(result, reports.resolve("idle.html"))
        table <- proofload.markdown(result)
        summary <- proofload.appendToStepSummary(
          result,
          (name: String) => Option.when(name == "GITHUB_STEP_SUMMARY")(summaryFile.toString),
        )
        index <- proofload.writePagesIndex(reports)
      yield assertTrue(
        Files.readString(page).contains("serve"),
        table.contains("serve"),
        summary == StepSummary.Appended(summaryFile),
        Files.readString(index).contains("idle.html"),
      )
    ,
    test("a spec off Actions is told so rather than failing"):
      for
        result <- proofload.run(idle.at(20.perSecond, over = 200.millis))
        summary <- proofload.appendToStepSummary(result, (_: String) => None)
      yield assertTrue(summary == StepSummary.NotOnActions.INSTANCE)
,
  )
