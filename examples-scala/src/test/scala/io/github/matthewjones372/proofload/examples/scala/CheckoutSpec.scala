package io.github.matthewjones372.proofload.examples.scala

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.java.Goals
import io.github.matthewjones372.proofload.java.Simulations
import io.github.matthewjones372.proofload.scala.apply
import io.github.matthewjones372.proofload.scala.exec
import io.github.matthewjones372.proofload.scala.given
import io.github.matthewjones372.proofload.scala.http
import io.github.matthewjones372.proofload.scala.perSecond
import io.github.matthewjones372.proofload.scala.scenario
import io.github.matthewjones372.proofload.scala.step
import io.github.matthewjones372.proofload.ziotest.proofload
import io.github.matthewjones372.proofload.ziotest.metItsGoals
import java.net.InetSocketAddress
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
        yield result.metItsGoals && assertTrue(
          result(browse).count > 0,
          result(browse).failed == 0L,
        ),
  )
