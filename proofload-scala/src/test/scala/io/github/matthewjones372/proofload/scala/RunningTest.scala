package io.github.matthewjones372.proofload.scala

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.Clock
import io.github.matthewjones372.proofload.Comparison
import io.github.matthewjones372.proofload.RunResultKt
import io.github.matthewjones372.proofload.java.Results
import java.net.InetSocketAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import _root_.scala.concurrent.duration.DurationInt

/**
 * A run rather than a construction: the claim is that a Scala caller reaches
 * the whole path, and only a real target proves the readers read what ran.
 */
class RunningTest:

  private val browse = step("browse")

  @Test
  def `a Scala caller runs a scenario and reads its percentiles`(): Unit =
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

      val result = Proofload().run(browsing.at(20.perSecond, over = 500.millis))

      assertTrue(result(browse).count > 0, "the run recorded nothing")
      assertEquals(result(browse).count, result(browse).ok)
      assertEquals(0L, result(browse).failed)
      assertTrue(result(browse).responseTime.p99 >= result(browse).responseTime.p50)
      assertTrue(result(browse).responseTime.max >= result(browse).responseTime.p99)
      assertTrue(result(browse).serviceTime.p95 >= _root_.scala.concurrent.duration.Duration.Zero)
      assertEquals(Results.p99(result, browse), asJava(result(browse).responseTime.p99))

      val offered = result.offered.get
      assertEquals(20.0, offered.asked.getPerSecond)
      assertTrue(offered.left.getPerSecond > 0.0, "nothing left, so nothing was offered")
      assertTrue(offered.over > _root_.scala.concurrent.duration.Duration.Zero)
      assertEquals(offered.left.getPerSecond / offered.asked.getPerSecond, offered.share)

      val comparison = result.against(result).asInstanceOf[Comparison.Compared]
      assertEquals("p99 of response time", comparison.getReadAt)
      assertEquals(
        "p50 of service time",
        result.against(result, percentile = 50.0, of = Clock.ServiceTime).asInstanceOf[Comparison.Compared].getReadAt,
      )

      assertEquals(RunResultKt.fellBehind(result), result.fellBehind)
      assertEquals(RunResultKt.lostGround(result), result.lostGround)
      assertFalse(result.ranOutOfRoom, "the injector ran out of its own room on ten requests")
      result.concurrency match
        case measured: Concurrency.Measured =>
          assertEquals(measured.fromResponseTime - measured.fromServiceTime, measured.backlog)
        case Concurrency.Absent(because) =>
          assertTrue(because.nonEmpty, "a run the law cannot be asked of has to say why")
    finally server.stop(0)
