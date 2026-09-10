package io.github.matthewjones372.proofload.ziotest

import io.github.matthewjones372.proofload.Engine
import io.github.matthewjones372.proofload.scala.apply
import io.github.matthewjones372.proofload.scala.at
import io.github.matthewjones372.proofload.scala.exec
import io.github.matthewjones372.proofload.scala.http
import io.github.matthewjones372.proofload.scala.perSecond
import io.github.matthewjones372.proofload.scala.scenario
import io.github.matthewjones372.proofload.scala.step
import java.net.InetSocketAddress
import java.net.ServerSocket
import zio.*
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/**
 * What reaches the error channel, and what deliberately does not.
 *
 * A refused connection is the second half: it is a thing the target did, so it
 * is measured and reported rather than thrown. A caller asking "did this fail"
 * of a run that reached no target at all would otherwise get the same answer
 * as one that reached a target and found it down.
 */
object ErrorsSpec extends ZIOSpecDefault:

  private val browse = step("browse")

  private def sending(throwing: Throwable): Engine = _ => throw throwing

  private val idle = scenario("idle")(exec(browse, io.github.matthewjones372.proofload.java.Actions.of(_ => ())))

  private val sample = idle.at(1.perSecond, over = 50.millis)

  private def closedPort = ZIO.attemptBlocking:
    val socket = ServerSocket(0, 0, InetSocketAddress(0).getAddress)
    val port = socket.getLocalPort
    socket.close()
    port

  def spec = suite("what reaches the error channel")(
    test("a simulation that cannot be run as written is Invalid"):
      for error <- proofload.run(sample, on = sending(IllegalArgumentException("upTo must be above zero"))).flip
      yield assertTrue(error.isInstanceOf[ProofloadError.Invalid])
    ,
    test("a run that did not finish is Interrupted"):
      for error <- proofload.run(sample, on = sending(InterruptedException("gone"))).flip
      yield assertTrue(error.isInstanceOf[ProofloadError.Interrupted])
    ,
    test("anything else is Failed, and carries what it was"):
      val bug = RuntimeException("the disk is full")

      for error <- proofload.run(sample, on = sending(bug)).flip
      yield assertTrue(error == ProofloadError.Failed(bug), error.getCause == bug)
    ,
    test("a target that refuses every connection is a measurement, not an error"):
      for
        port <- closedPort
        api = http.baseUrl(s"http://localhost:$port")
        refused = scenario("refused")(exec(browse, api.get("/products").expecting(200)))
        result <- proofload.run(refused.at(5.perSecond, over = 200.millis))
      yield assertTrue(
        result(browse).count > 0,
        result(browse).failed == result(browse).count,
      ),
  )
