package io.github.matthewjones372.kestrel.ziotest

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.Engine
import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.engine.VirtualThreads
import io.github.matthewjones372.kestrel.scala.apply
import io.github.matthewjones372.kestrel.scala.at
import io.github.matthewjones372.kestrel.scala.exec
import io.github.matthewjones372.kestrel.scala.http
import io.github.matthewjones372.kestrel.scala.perSecond
import io.github.matthewjones372.kestrel.scala.scenario
import io.github.matthewjones372.kestrel.scala.step
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import zio.ZIO
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import _root_.scala.concurrent.duration.DurationInt

/**
 * A run rather than a construction: the claim is that a zio-test spec reaches
 * the whole path, and only a real target proves it.
 */
object RunningSpec extends ZIOSpecDefault:

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

  def spec = suite("a load test that is a zio-test test")(
    test("runs a scenario against a target and reads what it measured"):
      ZIO.scoped:
        for
          server <- serving
          api = http.baseUrl(s"http://localhost:${server.getAddress.getPort}")
          browsing = scenario("browsing")(exec(browse, api.get("/products").expecting(200)))
          result <- kestrel.run(browsing.at(20.perSecond, over = 500.millis))
        yield assertTrue(
          result(browse).count > 0,
          result(browse).count == result(browse).ok,
          result(browse).failed == 0L,
          result(browse).responseTime.max >= result(browse).responseTime.p50,
        )
    ,
    test("sends the run on the blocking executor, not the pool the fiber is on"):
      val sending = AtomicReference("")
      val recording: Engine = simulation =>
        sending.set(Thread.currentThread.getName)
        VirtualThreads(Progress.Companion.getSilent).run(simulation)

      val idle = scenario("idle")()

      for
        onFiber <- ZIO.succeed(Thread.currentThread.getName)
        _ <- kestrel.run(idle.at(1.perSecond, over = 50.millis), on = recording)
      yield assertTrue(
        sending.get.startsWith("zio-default-blocking"),
        sending.get != onFiber,
      ),
  )
