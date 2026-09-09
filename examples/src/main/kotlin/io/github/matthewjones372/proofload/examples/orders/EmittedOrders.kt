// Written from orders.yaml by `proofload emit`.
//
// A plan file says what it can without a lambda. This is the same run in the
// language, which is where a capture, a condition or a body per user goes —
// none of which a plan can state, and none of which should be added to one.

package io.github.matthewjones372.proofload.examples.orders

import io.github.matthewjones372.proofload.Correlation
import io.github.matthewjones372.proofload.Scenario
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.completing
import io.github.matthewjones372.proofload.expecting
import io.github.matthewjones372.proofload.fedBy
import io.github.matthewjones372.proofload.feed
import io.github.matthewjones372.proofload.kafka.Header
import io.github.matthewjones372.proofload.kafka.completions
import io.github.matthewjones372.proofload.kafka.emit
import io.github.matthewjones372.proofload.kafka.kafka
import io.github.matthewjones372.proofload.p99
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.github.matthewjones372.proofload.step
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

val placeOrder = step("place order")
val confirmed = step("confirmed")
val cluster = kafka.brokers("localhost:9092")
val placeOrderTopic = cluster
    .setting("acks", "all")
    .topic("orders")
    .correlatedBy(Header("correlation-id"))
    .keyed { """anvil-1""".toByteArray() }
    .value { """{"cart":"1 anvil"}""".toByteArray() }
val confirmedTopic = cluster
    .setting("group.id", "proofload-bench")
    .topic("order-confirmations")
    .correlatedBy(Header("correlation-id"))
val user = sessionKey<Long>("user")
val byUser = Correlation { session -> session[user] ?: -1L }

val orders: Scenario = scenario("orders") {
    emit(placeOrder, placeOrderTopic, keyedBy = byUser)
}

val simulation = orders
    .at(500.0.perSecond, over = 1.minutes)
    .fedBy(feed(user) { it })
    .completing(
        confirmed,
        from = confirmedTopic.completions(),
        drainingFor = 30.seconds,
    )
    .expecting(
        p99(confirmed) under 2.seconds,
    )
