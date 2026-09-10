package io.github.matthewjones372.proofload.scala

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.report.StepSummary
import java.net.InetSocketAddress
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import _root_.scala.concurrent.duration.DurationInt

/**
 * The outputs, written from Scala without a file class, a hand-placed null or a
 * Kotlin function type. A real run rather than a built result: the pages are
 * made of what was measured.
 */
class ReportsTest:

  private val browse = step("browse")

  private def ran() =
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext(
      "/products",
      exchange =>
        exchange.sendResponseHeaders(200, 0)
        exchange.close(),
    )
    server.start()
    try
      val api = http.baseUrl(s"http://localhost:${server.getAddress.getPort}")
      val browsing = scenario("browsing")(exec(browse, api.get("/products").expecting(200)))
      Proofload().run(browsing.at(20.perSecond, over = 200.millis))
    finally server.stop(0)

  @Test
  def `a run writes its page, its table and its index`(): Unit =
    val result = ran()
    val reports = Files.createTempDirectory("proofload")

    val page = result.writeHtmlReport(reports.resolve("browsing.html"))
    val index = writePagesIndex(reports)

    assertTrue(Files.readString(page).contains("browse"))
    assertTrue(Files.readString(index).contains("browsing.html"))
    assertTrue(result.markdown.contains("browse"))
    assertEquals(result.toHtmlReport(), Files.readString(page))

  @Test
  def `a job summary is appended where the variable names one, and skipped where it does not`(): Unit =
    val result = ran()
    val summary = Files.createTempDirectory("proofload").resolve("summary.md")

    assertInstanceOf(
      classOf[StepSummary.NotOnActions],
      result.appendToStepSummary(environment = _ => None),
    )
    assertInstanceOf(
      classOf[StepSummary.Appended],
      result.appendToStepSummary(environment = name => Option.when(name == "GITHUB_STEP_SUMMARY")(summary.toString)),
    )
    assertTrue(Files.readString(summary).contains("browse"))
