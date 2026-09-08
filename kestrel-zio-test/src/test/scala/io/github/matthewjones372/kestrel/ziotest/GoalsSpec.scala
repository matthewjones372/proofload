package io.github.matthewjones372.kestrel.ziotest

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.java.Goals
import io.github.matthewjones372.kestrel.java.Simulations
import io.github.matthewjones372.kestrel.scala.exec
import io.github.matthewjones372.kestrel.scala.http
import io.github.matthewjones372.kestrel.scala.perSecond
import io.github.matthewjones372.kestrel.scala.scenario
import io.github.matthewjones372.kestrel.scala.step
import java.net.InetSocketAddress
import java.time.Duration as JavaDuration
import zio.ZIO
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/**
 * A goal the run declares says which one missed and by how much; an assertion
 * says only that a number was too big. Both belong, and this is the first.
 */
object GoalsSpec extends ZIOSpecDefault:

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

  private def ran(port: Int, goals: io.github.matthewjones372.kestrel.Goal*): ZIO[Any, Throwable, RunResult] =
    val api = http.baseUrl(s"http://localhost:$port")
    val browsing = scenario("browsing")(exec(browse, api.get("/products").expecting(200)))
    kestrel.run(Simulations.at(browsing, 20.perSecond, JavaDuration.ofMillis(300), goals*))

  def spec = suite("the goals a run declared")(
    test("a run that met every goal it declared passes"):
      ZIO.scoped:
        for
          server <- serving
          result <- ran(server.getAddress.getPort, Goals.failureRateUnder(0.1))
        yield assertTrue(missedGoals(result).isEmpty, result.metItsGoals.isSuccess)
    ,
    test("a missed goal is named, with what the run was asked for"):
      ZIO.scoped:
        for
          server <- serving
          result <- ran(
            server.getAddress.getPort,
            Goals.failureRateUnder(0.1),
            Goals.p99Under(browse, JavaDuration.ofNanos(1)),
          )
        yield assertTrue(
          missedGoals(result).size == 1,
          missedGoals(result).head.contains("browse p99 under"),
          missedGoals(result).head.startsWith("missed"),
          result.metItsGoals.isFailure,
        ),
  )
