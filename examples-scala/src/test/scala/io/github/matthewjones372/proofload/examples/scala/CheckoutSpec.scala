package io.github.matthewjones372.proofload.examples.scala

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.java.Goals
import io.github.matthewjones372.proofload.java.Simulations
import io.github.matthewjones372.proofload.scala.apply
import io.github.matthewjones372.proofload.scala.appendToStepSummary
import io.github.matthewjones372.proofload.scala.at
import io.github.matthewjones372.proofload.scala.curve
import io.github.matthewjones372.proofload.scala.exec
import io.github.matthewjones372.proofload.scala.feed
import io.github.matthewjones372.proofload.scala.fedBy
import io.github.matthewjones372.proofload.scala.given
import io.github.matthewjones372.proofload.scala.http
import io.github.matthewjones372.proofload.scala.markdown
import io.github.matthewjones372.proofload.scala.offered
import io.github.matthewjones372.proofload.scala.perSecond
import io.github.matthewjones372.proofload.scala.rate
import io.github.matthewjones372.proofload.scala.`+`
import io.github.matthewjones372.proofload.scala.scenario
import io.github.matthewjones372.proofload.scala.sessionKey
import io.github.matthewjones372.proofload.scala.step
import io.github.matthewjones372.proofload.scala.sustainable
import io.github.matthewjones372.proofload.scala.writeHtmlReport
import io.github.matthewjones372.proofload.scala.writePagesIndex
import io.github.matthewjones372.proofload.ziotest.proofload
import io.github.matthewjones372.proofload.ziotest.metItsGoals
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import zio.ZIO
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import _root_.scala.concurrent.duration.DurationInt
import _root_.scala.language.implicitConversions

/**
 * A load test that is a zio-test test, and the gate on `proofload-zio-test`
 * being usable from outside its own module.
 *
 * It asserts nothing about how long a request took. A limit that holds on a
 * laptop and not on a shared runner is a test that gets deleted, so the
 * absolute numbers are on the page and in `Checkout`, which is compiled and
 * not run.
 */
object CheckoutSpec extends ZIOSpecDefault:

  private val browse = step("browse")

  private val pathTo = step("path to")

  private val personId = sessionKey[String]("personId")

  private val target = sessionKey[String]("target")

  private val serving = ZIO.acquireRelease(
    ZIO.attemptBlocking:
      val server = HttpServer.create(InetSocketAddress(0), 0)
      server.createContext(
        "/products",
        exchange =>
          exchange.sendResponseHeaders(200, 0)
          exchange.close(),
      )
      server.start()
      server,
  )(server => ZIO.succeed(server.stop(0)))

  private def counting(seen: java.util.Set[String]) = ZIO.acquireRelease(
    ZIO.attemptBlocking:
      val server = HttpServer.create(InetSocketAddress(0), 0)
      server.createContext(
        "/people",
        exchange =>
          seen.add(exchange.getRequestURI.getPath)
          exchange.sendResponseHeaders(200, 0)
          exchange.close(),
      )
      server.start()
      server,
  )(server => ZIO.succeed(server.stop(0)))

  def spec = suite("checkout")(
    test("holds its failure rate at 20 a second"):
      ZIO.scoped:
        for
          server <- serving
          api = http.baseUrl(s"http://localhost:${server.getAddress.getPort}")
          browsing = scenario("browsing")(exec(browse, api.get("/products").expecting(200)))
          result <- proofload.run(
            Simulations.at(browsing, 20.perSecond, 500.millis, Goals.failureRateUnder(0.1)),
          )
          reports <- ZIO.attemptBlocking(Files.createTempDirectory("proofload"))
          _ <- ZIO.attemptBlocking:
            result.writeHtmlReport(reports.resolve("checkout.html"))
            result.appendToStepSummary()
            writePagesIndex(reports)
        yield result.metItsGoals && assertTrue(
          result(browse).count > 0,
          result(browse).failed == 0L,
          result.markdown.contains("browse"),
        ),
    test("hunts for the rate it holds that failure rate at"):
      ZIO.scoped:
        for
          server <- serving
          api = http.baseUrl(s"http://localhost:${server.getAddress.getPort}")
          browsing = scenario("browsing")(exec(browse, api.get("/products").expecting(200)))
          capacity <- proofload.run(
            browsing.sustainable(upTo = 100.perSecond, holding = 500.millis, Goals.failureRateUnder(50)),
          )
        yield assertTrue(
          capacity.curve.nonEmpty,
          capacity.curve.forall(rung => rung.rate.getPerSecond > 0.0),
          capacity.curve.forall(rung => rung.offered.getPerSecond > 0.0),
        ),
    test("asks about a different pair of characters for every user"):
      ZIO.scoped:
        val seen = ConcurrentHashMap.newKeySet[String]()
        for
          server <- counting(seen)
          api = http.baseUrl(s"http://localhost:${server.getAddress.getPort}")
          graph = scenario("graph")(exec(pathTo, api.get("/people/{personId}/path-to/{target}").expecting(200)))
          result <- proofload.run(
            graph
              .at(20.perSecond, over = 500.millis)
              .fedBy(feed(personId)(user => (user % 82 + 1).toString) + feed(target)(user => (user % 61 + 7).toString)),
          )
        yield assertTrue(
          result(pathTo).failed == 0L,
          seen.size.toLong == result(pathTo).count,
        ),
  )
