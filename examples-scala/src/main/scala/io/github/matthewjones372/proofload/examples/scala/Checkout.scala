package io.github.matthewjones372.proofload.examples.scala

import io.github.matthewjones372.proofload.java.Https
import io.github.matthewjones372.proofload.java.Results
import io.github.matthewjones372.proofload.java.Simulations
import io.github.matthewjones372.proofload.scala.Proofload
import io.github.matthewjones372.proofload.scala.apply
import io.github.matthewjones372.proofload.scala.exec
import io.github.matthewjones372.proofload.scala.failureRate
import io.github.matthewjones372.proofload.scala.given
import io.github.matthewjones372.proofload.scala.http
import io.github.matthewjones372.proofload.scala.p99
import io.github.matthewjones372.proofload.scala.pause
import io.github.matthewjones372.proofload.scala.percent
import io.github.matthewjones372.proofload.scala.perSecond
import io.github.matthewjones372.proofload.scala.scenario
import io.github.matthewjones372.proofload.scala.sessionKey
import io.github.matthewjones372.proofload.scala.step
import _root_.scala.concurrent.duration.DurationInt
import _root_.scala.language.implicitConversions

/**
 * A load test written in Scala, and the gate on `proofload-scala` being callable
 * from it. No name here carries a value-class hash and no duration here is a
 * bare `Long`; an extension that goes takes this source set with it.
 */
object Checkout:

  private val orderId = sessionKey[String]("orderId")

  private val browse = step("browse")

  private val placeOrder = step("place order")

  def main(args: Array[String]): Unit =
    val api = http.baseUrl("https://orders.internal")

    val checkout = scenario("checkout")(
      exec(browse, api.get("/products").expecting(200)),
      exec(
        placeOrder,
        Https.capturing(
          api.post("/orders").body("""{"cart":"1 anvil"}""").expecting(201),
          orderId,
          response => response.header("location"),
        ),
      ),
      pause(1.second),
    )

    val result = Proofload().run(
      Simulations.at(
        checkout,
        50.perSecond,
        1.minute,
        p99(placeOrder) under 200.millis,
        failureRate under 0.1.percent,
      ),
    )

    Results.verdicts(result).forEach: verdict =>
      println(verdict.getGoal.getDescribed + (if verdict.getMet then " met" else " missed"))

    val tail = result(placeOrder).responseTime.p99
    println(s"${placeOrder.getName} p99 ${tail.toMillis}ms over ${result(placeOrder).count} requests, " +
      s"${result(placeOrder).failed} failed")
