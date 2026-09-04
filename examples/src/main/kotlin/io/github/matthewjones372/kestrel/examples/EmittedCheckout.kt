// Written from checkout.yaml by `kestrel emit`.
//
// A plan file says what it can without a lambda. This is the same run in the
// language, which is where a capture, a condition or a body per user goes —
// none of which a plan can state, and none of which should be added to one.

package io.github.matthewjones372.kestrel.examples

import io.github.matthewjones372.kestrel.Scenario
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.expecting
import io.github.matthewjones372.kestrel.http.http
import io.github.matthewjones372.kestrel.p99
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.step
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

val browse = step("browse")
val placeOrder = step("place order")
val api = http.baseUrl("https://orders.internal")

val checkout: Scenario = scenario("checkout") {
    exec(browse, api.get("/products"))
    exec(placeOrder, api.post("/orders").body("""{"cart":"1 anvil"}""").expecting(201))
}

val simulation = checkout
    .at(50.0.perSecond, over = 1.minutes)
    .expecting(
        p99(placeOrder) under 200.milliseconds,
    )
