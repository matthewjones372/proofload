package io.github.matthewjones372.kestrel.examples.scala

import io.github.matthewjones372.kestrel.java.Goals
import io.github.matthewjones372.kestrel.java.Https
import io.github.matthewjones372.kestrel.java.Results
import io.github.matthewjones372.kestrel.java.Simulations
import io.github.matthewjones372.kestrel.scala.Kestrel
import io.github.matthewjones372.kestrel.scala.apply
import io.github.matthewjones372.kestrel.scala.exec
import io.github.matthewjones372.kestrel.scala.given
import io.github.matthewjones372.kestrel.scala.http
import io.github.matthewjones372.kestrel.scala.pause
import io.github.matthewjones372.kestrel.scala.perSecond
import io.github.matthewjones372.kestrel.scala.scenario
import io.github.matthewjones372.kestrel.scala.sessionKey
import io.github.matthewjones372.kestrel.scala.step
import _root_.scala.concurrent.duration.DurationInt
import _root_.scala.language.implicitConversions

/**
 * A load test written in Scala, and the gate on `kestrel-scala` being callable
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

    // The goals go through the Java facade, and take `200.millis` because the
    // conversion is given: this is what the `implicitConversions` import above
    // is for.
    val result = Kestrel().run(
      Simulations.at(
        checkout,
        50.perSecond,
        1.minute,
        Goals.p99Under(placeOrder, 200.millis),
        Goals.failureRateUnder(0.1),
      ),
    )

    Results.verdicts(result).forEach: verdict =>
      println(verdict.getGoal.getDescribed + (if verdict.getMet then " met" else " missed"))

    val tail = result(placeOrder).responseTime.p99
    println(s"${placeOrder.getName} p99 ${tail.toMillis}ms over ${result(placeOrder).count} requests, " +
      s"${result(placeOrder).failed} failed")
